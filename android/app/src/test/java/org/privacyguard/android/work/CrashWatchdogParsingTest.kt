package org.privacyguard.android.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CrashWatchdogParsingTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Test
    fun `test subreason 4 on other reason does not trigger crash detection`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:10.000
              pid=12345
              realUid=10123
              package=com.example.app
              process=com.example.app
              reason=13 (OTHER)
              subreason=4 (MEMORY_TRIM)
              status=0
        """.trimIndent()

        // reason=13 is not a crash, subreason=4 should NOT trigger hasCrashInWindow
        assertFalse(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test crash reason 4 within window triggers detection`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:25.000
              pid=12345
              realUid=10123
              package=com.example.app
              process=com.example.app
              reason=4 (CRASH)
              subreason=0
              status=0
        """.trimIndent()

        assertTrue(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test native crash reason 5 within window triggers detection`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:15.500
              reason=5 (CRASH_NATIVE)
              package=com.example.app
        """.trimIndent()

        assertTrue(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test ANR reason 6 within window triggers detection`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:40.000
              reason=6 (ANR)
              package=com.example.app
        """.trimIndent()

        assertTrue(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test user requested exit reason 10 does not trigger crash detection`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:20.000
              reason=10 (USER_REQUESTED)
              package=com.example.app
        """.trimIndent()

        assertFalse(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test crash outside window does not trigger detection`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        // Timestamp is 2 minutes later, outside the 60s window
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:02:10.000
              reason=4 (CRASH)
              package=com.example.app
        """.trimIndent()

        assertFalse(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test numeric epoch timestamp fallback within window`() {
        val baseTime = 1731580800000L
        val crashTime = baseTime + 15_000L // 15s into window
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=$crashTime
              reason=4 (CRASH)
              package=com.example.app
        """.trimIndent()

        assertTrue(CrashWatchdogWorker.hasCrashInWindow(dumpBlock, baseTime, 60_000L))
    }

    @Test
    fun `test multi-block dumpsys with past crash and current benign exit`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val multiBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:15.000
              reason=10 (USER_REQUESTED)
            ApplicationExitInfo #2:
              timestamp=2026-11-14 11:30:00.000
              reason=4 (CRASH)
        """.trimIndent()

        // Exit #1 is in window but not a crash; Exit #2 is a crash but 30 minutes before window
        assertFalse(CrashWatchdogWorker.hasCrashInWindow(multiBlock, baseTime, 60_000L))
    }
}
