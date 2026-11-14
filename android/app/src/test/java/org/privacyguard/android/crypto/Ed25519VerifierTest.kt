package org.privacyguard.android.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Ed25519VerifierTest {

    private fun getAssetFile(name: String): File {
        val candidates = listOf(
            File("src/main/assets", name),
            File("app/src/main/assets", name),
            File("android/app/src/main/assets", name)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Asset $name not found in candidates: $candidates")
    }

    @Test
    fun testTrackersAcSignatureVerification() {
        val dataFile = getAssetFile("trackers_ac.bin")
        val sigFile = getAssetFile("trackers_ac.bin.sig")

        val data = dataFile.readBytes()
        val sig = sigFile.readBytes()

        val verified = Ed25519Verifier.verify(data, sig)
        assertTrue("trackers_ac.bin must pass Ed25519 verification against pinned SPKI key", verified)
    }

    @Test
    fun testLgbmModelSignatureVerification() {
        val dataFile = getAssetFile("lgbm_model.bin")
        val sigFile = getAssetFile("lgbm_model.bin.sig")

        val data = dataFile.readBytes()
        val sig = sigFile.readBytes()

        val verified = Ed25519Verifier.verify(data, sig)
        assertTrue("lgbm_model.bin must pass Ed25519 verification against pinned SPKI key", verified)
    }

    @Test
    fun testTamperedDataFailsVerification() {
        val dataFile = getAssetFile("trackers_ac.bin")
        val sigFile = getAssetFile("trackers_ac.bin.sig")

        val data = dataFile.readBytes()
        val sig = sigFile.readBytes()

        // Flip one bit
        data[0] = (data[0].toInt() xor 0xFF).toByte()

        val verified = Ed25519Verifier.verify(data, sig)
        assertFalse("Tampered asset data must fail Ed25519 verification", verified)
    }

    @Test
    fun testCorruptedSignatureFailsVerification() {
        val dataFile = getAssetFile("trackers_ac.bin")
        val sigFile = getAssetFile("trackers_ac.bin.sig")

        val data = dataFile.readBytes()
        val sig = sigFile.readBytes()

        // Truncate signature
        val truncatedSig = sig.copyOf(63)
        assertFalse("Truncated signature must fail", Ed25519Verifier.verify(data, truncatedSig))

        // Corrupt signature bytes
        sig[10] = (sig[10].toInt() xor 0xFF).toByte()
        assertFalse("Corrupted signature byte must fail", Ed25519Verifier.verify(data, sig))
    }
}
