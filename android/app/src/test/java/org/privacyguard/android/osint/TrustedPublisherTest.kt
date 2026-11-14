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
    fun `unpinned packages fail gate`() {
        val anyCert = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"
        assertFalse(OsintClassifierWorker.isTrustedPublisher(anyCert, "org.fdroid.fdroid"))
        assertFalse(OsintClassifierWorker.isTrustedPublisher(anyCert, "org.thoughtcrime.securesms"))
        assertFalse(OsintClassifierWorker.isTrustedPublisher(anyCert, "org.mozilla.firefox"))
    }
}
