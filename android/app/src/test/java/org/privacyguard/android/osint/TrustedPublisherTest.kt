package org.privacyguard.android.osint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedPublisherTest {

    @Test
    fun `gms package with matching pinned cert passes gate`() {
        val leafCert = "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402"
        val originalCert = "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"

        assertTrue(OsintClassifierWorker.isTrustedPublisher(leafCert, "com.google.android.gms"))
        assertTrue(OsintClassifierWorker.isTrustedPublisher(originalCert, "com.google.android.gms"))
        assertTrue(OsintClassifierWorker.isTrustedPublisher(leafCert.uppercase(), "com.google.android.gms"))
    }

    @Test
    fun `gms package with mismatched cert fails gate`() {
        val imposterCert = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"
        assertFalse(OsintClassifierWorker.isTrustedPublisher(imposterCert, "com.google.android.gms"))
    }

    @Test
    fun `attacker package using google cert fails gate due to package pin mismatch`() {
        val googleCert = "5f2391277b1dbd489000467e4c2fa6af802430080457dce2f618992e9dfb5402"
        assertFalse(OsintClassifierWorker.isTrustedPublisher(googleCert, "com.evil.malware"))
        assertFalse(OsintClassifierWorker.isTrustedPublisher(googleCert, "com.google.android.gms.fake"))
    }

    @Test
    fun `real fdroid signal duckduckgo and mozilla certs with their packages pass gate`() {
        val fdroidCert = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
        assertTrue(OsintClassifierWorker.isTrustedPublisher(fdroidCert, "org.fdroid.fdroid"))

        val signalCert = "29f34e5f27f21172404b86963ff36090d026338d30248232b60a4630737d0c65"
        assertTrue(OsintClassifierWorker.isTrustedPublisher(signalCert, "org.thoughtcrime.securesms"))

        val ddgCert = "a93540e90c749ef15494cb3bf1d2105c317faab2d735fbfaaa0f9c2d1b848039"
        assertTrue(OsintClassifierWorker.isTrustedPublisher(ddgCert, "com.duckduckgo.mobile.android"))

        val mozillaCert = "80292bf185123d4512c1c6676f2f9c57d77b28203f1eb9c97b2d56a2bb0364c6"
        assertTrue(OsintClassifierWorker.isTrustedPublisher(mozillaCert, "org.mozilla.firefox"))
    }
}
