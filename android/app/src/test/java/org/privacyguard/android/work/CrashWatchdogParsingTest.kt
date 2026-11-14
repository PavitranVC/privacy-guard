package org.privacyguard.android.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class CrashWatchdogParsingTest {

    private val reasonRegex = Regex("""(?<![A-Za-z])reason=([4-7])\b""")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Test
    fun `test subreason does not falsely trigger reason regex`() {
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:00.000
              pid=12345
              realUid=10123
              package=com.example.app
              process=com.example.app
              reason=13 (OTHER)
              subreason=4 (MEMORY_TRIM)
              status=0
        """.trimIndent()

        // reason=13 is not a crash, subreason=4 should NOT match (?<![A-Za-z])reason=([4-7])\b
        assertFalse(reasonRegex.containsMatchIn(dumpBlock))
    }

    @Test
    fun `test crash reason triggers reason regex`() {
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:00:00.000
              pid=12345
              realUid=10123
              package=com.example.app
              process=com.example.app
              reason=4 (CRASH)
              subreason=0
              status=0
        """.trimIndent()

        assertTrue(reasonRegex.containsMatchIn(dumpBlock))
        val match = reasonRegex.find(dumpBlock)
        assertNotNull(match)
        assertEquals("4", match!!.groupValues[1])
    }

    @Test
    fun `test timestamp regex parses full date and time without truncating time`() {
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=2026-11-14 12:30:45.123
              reason=5 (CRASH_NATIVE)
        """.trimIndent()

        val tsMatch = Regex("""timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""").find(dumpBlock)
        assertNotNull(tsMatch)
        val rawTs = tsMatch!!.groupValues[1]
        assertEquals("2026-11-14 12:30:45.123", rawTs)

        val parsedTime = sdf.parse(rawTs)?.time
        assertNotNull(parsedTime)
        assertTrue(parsedTime!! > 0L)
    }

    @Test
    fun `test numeric timestamp fallback`() {
        val dumpBlock = """
            ApplicationExitInfo #1:
              timestamp=1731580800000
              reason=6 (ANR)
        """.trimIndent()

        val tsMatch = Regex("""timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})""").find(dumpBlock)
        assertEquals(null, tsMatch)

        val numericMatch = Regex("""timestamp=(\d{10,13})\b""").find(dumpBlock)
        assertNotNull(numericMatch)
        assertEquals(1731580800000L, numericMatch!!.groupValues[1].toLongOrNull())
    }
}
