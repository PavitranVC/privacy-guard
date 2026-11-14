package org.privacyguard.android.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CrashWatchdogParsingTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Test
    fun `synthetic test subreason 4 on other reason does not trigger crash detection`() {
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
    fun `synthetic test crash reason 4 within window triggers detection`() {
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
    fun `synthetic test native crash reason 5 within window triggers detection`() {
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
    fun `synthetic test ANR reason 6 within window triggers detection`() {
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
    fun `synthetic test user requested exit reason 10 does not trigger crash detection`() {
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
    fun `synthetic test crash outside window does not trigger detection`() {
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
    fun `synthetic test numeric epoch timestamp fallback within window`() {
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
    fun `synthetic test multi-block dumpsys with past crash and current benign exit`() {
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

    @Test
    fun `synthetic test subreason 4 on same line as reason 13 within window returns false`() {
        val baseTime = sdf.parse("2026-11-14 12:00:00.000")!!.time
        val sameLineDump = """
            ApplicationExitInfo #0:
              timestamp=2026-11-14 12:00:10.000 pid=1234
              process=com.example.app reason=13 (OTHER KILLS BY SYSTEM) subreason=4 (ISOLATED NOT NEEDED) status=0
        """.trimIndent()

        // subreason=4 must not trigger reason=4 regex
        assertFalse(CrashWatchdogWorker.hasCrashInWindow(sameLineDump, baseTime, 60_000L))
    }

    @Test
    fun `test real moto g54 restore fixture has no crash in window for any entry`() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("fixtures/moto_g54_sdk35_exit_info.txt")!!
            .bufferedReader().readText()

        val tsRegex = Regex("""timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""")
        val matches = tsRegex.findAll(fixture).toList()
        assertTrue(matches.isNotEmpty())

        for (match in matches) {
            val entryTime = sdf.parse(match.groupValues[1])!!.time
            // For every entry timestamp T, hasCrashInWindow(fixture, T-5s, 60s) == false
            assertFalse(CrashWatchdogWorker.hasCrashInWindow(fixture, entryTime - 5_000L, 60_000L))
        }
    }

    @Test
    fun `test real moto g54 crash fixture detects reason 4 within window and false outside`() {
        val crashFixture = javaClass.classLoader!!.getResourceAsStream("fixtures/moto_g54_sdk35_exit_info_crash.txt")!!
            .bufferedReader().readText()

        // Find the entry that has reason=4
        val blocks = crashFixture.split("ApplicationExitInfo #")
        var crashTimestamp: Long? = null
        val tsRegex = Regex("""timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""")
        val reasonRegex = Regex("""(?<![A-Za-z])reason=4\b""")

        for (block in blocks) {
            if (reasonRegex.containsMatchIn(block)) {
                val match = tsRegex.find(block)
                if (match != null) {
                    crashTimestamp = sdf.parse(match.groupValues[1])?.time
                    break
                }
            }
        }

        assertTrue("Crash fixture must contain a reason=4 entry", crashTimestamp != null)
        val t = crashTimestamp!!

        // For the reason=4 entry timestamp T:
        // hasCrashInWindow(fixture, T-5s, 60s) == true
        assertTrue(CrashWatchdogWorker.hasCrashInWindow(crashFixture, t - 5_000L, 60_000L))
        // and hasCrashInWindow(fixture, T+120s, 60s) == false
        assertFalse(CrashWatchdogWorker.hasCrashInWindow(crashFixture, t + 120_000L, 60_000L))
    }
}
