package org.privacyguard.android.storage

object TrimCachesCommandBuilder {

    const val DELTA_256_MB = 256L * 1024L * 1024L // 268,435,456 bytes
    const val DELTA_512_MB = 512L * 1024L * 1024L // 536,870,912 bytes
    const val DELTA_1_GB = 1024L * 1024L * 1024L  // 1,073,741,824 bytes

    fun computeTargetBytes(currentFreeBytes: Long, deltaBytes: Long): Long {
        require(currentFreeBytes >= 0L) {
            "currentFreeBytes must be non-negative, got: $currentFreeBytes"
        }
        require(deltaBytes > 0L) {
            "deltaBytes must be strictly positive, got: $deltaBytes"
        }
        if (currentFreeBytes > Long.MAX_VALUE - deltaBytes) {
            throw IllegalArgumentException(
                "Target bytes overflow Long.MAX_VALUE: currentFreeBytes=$currentFreeBytes, deltaBytes=$deltaBytes"
            )
        }
        return currentFreeBytes + deltaBytes
    }

    fun buildCommand(currentFreeBytes: Long, deltaBytes: Long): Array<String> {
        val target = computeTargetBytes(currentFreeBytes, deltaBytes)
        return arrayOf("pm", "trim-caches", target.toString())
    }

    fun computeFreedBytes(beforeFreeBytes: Long, afterFreeBytes: Long): Long {
        return maxOf(0L, afterFreeBytes - beforeFreeBytes)
    }
}
