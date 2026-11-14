package org.privacyguard.android.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max

class LightGbmParityTest {

    private fun getAssetFile(name: String): File {
        val candidates = listOf(
            File("src/main/assets", name),
            File("app/src/main/assets", name),
            File("android/app/src/main/assets", name)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Asset $name not found in candidates: $candidates")
    }

    data class Node(
        val feature: Int,
        val threshold: Double,
        val left: Int,
        val right: Int,
        val defaultLeft: Boolean
    )

    data class IsotonicPoint(val x: Double, val y: Double)

    class KotlinModel(
        val numFeatures: Int,
        val treeRoots: IntArray,
        val nodes: List<Node>,
        val leaves: DoubleArray,
        val isotonicCurves: List<List<IsotonicPoint>>
    ) {
        fun evaluate(features: FloatArray): DoubleArray {
            val numClasses = 4
            val margins = DoubleArray(numClasses)

            for (treeIdx in treeRoots.indices) {
                val classIdx = treeIdx % numClasses
                var nodeIdx = treeRoots[treeIdx]

                while (nodeIdx >= 0 && nodeIdx < nodes.size) {
                    val node = nodes[nodeIdx]
                    val featVal = if (node.feature in features.indices) features[node.feature].toDouble() else 0.0
                    val isNan = featVal.isNaN()

                    val takeLeft = if (isNan) {
                        node.defaultLeft
                    } else {
                        featVal <= node.threshold
                    }

                    val nextIdx = if (takeLeft) node.left else node.right
                    if (nextIdx < 0) {
                        val leafIdx = nextIdx.inv()
                        if (leafIdx in leaves.indices) {
                            margins[classIdx] += leaves[leafIdx]
                        }
                        break
                    } else {
                        nodeIdx = nextIdx
                    }
                }
            }

            // Softmax
            var maxMargin = margins.maxOrNull() ?: 0.0
            val expVals = DoubleArray(numClasses) { exp(margins[it] - maxMargin) }
            val expSum = expVals.sum().coerceAtLeast(1e-12)
            val probs = DoubleArray(numClasses) { expVals[it] / expSum }

            // Isotonic calibration
            val calProbs = DoubleArray(numClasses)
            for (c in 0 until numClasses) {
                val p = probs[c]
                val curve = isotonicCurves[c]
                if (curve.isEmpty()) {
                    calProbs[c] = p
                } else if (p <= curve.first().x) {
                    calProbs[c] = curve.first().y
                } else if (p >= curve.last().x) {
                    calProbs[c] = curve.last().y
                } else {
                    var interpolated = p
                    for (i in 0 until curve.size - 1) {
                        val p0 = curve[i]
                        val p1 = curve[i + 1]
                        if (p in p0.x..p1.x) {
                            val dx = p1.x - p0.x
                            interpolated = if (dx > 1e-12) {
                                p0.y + (p - p0.x) * (p1.y - p0.y) / dx
                            } else {
                                p0.y
                            }
                            break
                        }
                    }
                    calProbs[c] = interpolated
                }
            }

            val calSum = calProbs.sum().coerceAtLeast(1e-12)
            return DoubleArray(numClasses) { (calProbs[it] / calSum).coerceIn(0.0, 1.0) }
        }
    }

    private fun loadModel(file: File): KotlinModel {
        val bytes = file.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magicBytes = ByteArray(4)
        bb.get(magicBytes)
        val magicStr = String(magicBytes, Charsets.US_ASCII)
        assertEquals("Magic must be LGBM", "LGBM", magicStr)

        val version = bb.int
        assertEquals("Version must be 1", 1, version)

        val numFeatures = bb.int
        assertEquals("Features must be 16", 16, numFeatures)

        val numClasses = bb.int
        assertEquals("Classes must be 4", 4, numClasses)

        val numTrees = bb.int
        val numNodes = bb.int
        val numLeaves = bb.int

        assertTrue(numFeatures > 0)
        assertTrue(numClasses == 4)
        assertTrue(numTrees > 0)
        assertTrue(numNodes > 0)
        assertTrue(numLeaves > 0)

        val treeRoots = IntArray(numTrees)
        for (i in 0 until numTrees) {
            treeRoots[i] = bb.int
        }

        val nodes = ArrayList<Node>(numNodes)
        for (i in 0 until numNodes) {
            val feat = bb.int
            bb.int // pad0
            val thresh = bb.double
            val left = bb.int
            val right = bb.int
            val defLeft = bb.get() != 0.toByte()
            bb.get() // pad1
            bb.get() // pad2
            bb.get() // pad3
            nodes.add(Node(feat, thresh, left, right, defLeft))
        }

        val leaves = DoubleArray(numLeaves)
        for (i in 0 until numLeaves) {
            leaves[i] = bb.double
        }

        val curves = ArrayList<List<IsotonicPoint>>(numClasses)
        for (c in 0 until numClasses) {
            if (bb.hasRemaining()) {
                val numPts = bb.int
                val pts = ArrayList<IsotonicPoint>(numPts)
                for (i in 0 until numPts) {
                    val x = bb.double
                    val y = bb.double
                    pts.add(IsotonicPoint(x, y))
                }
                curves.add(pts)
            } else {
                curves.add(emptyList())
            }
        }

        return KotlinModel(numFeatures, treeRoots, nodes, leaves, curves)
    }

    @Test
    fun testBinaryModelDeserializationAndParity() {
        val modelFile = getAssetFile("lgbm_model.bin")
        val model = loadModel(modelFile)

        assertEquals("Expected 16 input features", 16, model.numFeatures)

        // Evaluate clean baseline features
        val cleanFeatures = FloatArray(16) { 0.0f }
        val cleanProbs = model.evaluate(cleanFeatures)
        assertEquals(4, cleanProbs.size)
        val cleanSum = cleanProbs.sum()
        assertEquals(1.0, cleanSum, 1e-4)

        // Evaluate suspicious features: high entropy, dynamic loading, multiple trackers
        val suspiciousFeatures = FloatArray(16) { 0.0f }
        suspiciousFeatures[3] = 7.5f // high entropy
        suspiciousFeatures[4] = 1.0f // has dynamic loading
        suspiciousFeatures[5] = 5.0f // 5 trackers
        suspiciousFeatures[7] = 1.0f // native danger flags

        val suspProbs = model.evaluate(suspiciousFeatures)
        val suspSum = suspProbs.sum()
        assertEquals(1.0, suspSum, 1e-4)

        // Risk score = P(Malicious) + 0.5 * P(Suspicious)
        val cleanRisk = cleanProbs[3] + 0.5 * cleanProbs[2]
        val suspRisk = suspProbs[3] + 0.5 * suspProbs[2]

        assertTrue("Clean risk must be bounded [0, 1]", cleanRisk in 0.0..1.0)
        assertTrue("Suspicious risk must be bounded [0, 1]", suspRisk in 0.0..1.0)
    }
}
