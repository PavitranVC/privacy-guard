package org.privacyguard.android.crypto

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object Ed25519Verifier {

    // Pinned X.509 SPKI Ed25519 public key
    const val PINNED_PUB_KEY_BASE64 = "MCowBQYDK2VwAyEAgCYpThvpyHUz6FbXD+eFlCHG818ajDs/UOcGH6HV6ew="

    private val publicKey: PublicKey by lazy {
        val keyBytes = Base64.getDecoder().decode(PINNED_PUB_KEY_BASE64)
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
