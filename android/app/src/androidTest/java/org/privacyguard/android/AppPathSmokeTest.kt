package org.privacyguard.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class AppPathSmokeTest {

    @Test
    fun testTargetContextPackageName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.privacyguard.android", context.packageName)
    }

    @Test
    fun testShizukuPingBinder() {
        assertTrue("Shizuku binder must be alive and pingable", Shizuku.pingBinder())
    }
}
