package org.privacyguard.android.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object Ed25519Verifier {

    // Pinned X.509 SPKI Ed25519 public key
    private const val PINNED_PUB_KEY_BASE64 = "MCowBQYDK2VwAyEACP2r7c1ApFzKAxquv7K0Piq8zWDm4huiS8xB+iTreUY="

    private val publicKey: PublicKey by lazy {
        val keyBytes = Base64.decode(PINNED_PUB_KEY_BASE64, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("Ed25519").generatePublic(keySpec)
    }

    /**
     * Verifies that the provided data matches the detached 64-byte signature
     * using the pinned Ed25519 public key.
     */
    fun verify(data: ByteArray, signatureBytes: ByteArray): Boolean {
        if (signatureBytes.size != 64) return false
        return try {
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signatureBytes)
        } catch (e: Throwable) {
            false
        }
    }
}
