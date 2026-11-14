package org.privacyguard.android.shizuku

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellOpsSnapshotTest {

    @Test
    fun `mergeSnapshots with null or blank existing returns current`() {
        val current = """{"com.example.ServiceA":1,"com.example.ReceiverB":2}"""

        assertEquals(current, ShellOps.mergeSnapshots(null, current))
        assertEquals(current, ShellOps.mergeSnapshots("", current))
        assertEquals(current, ShellOps.mergeSnapshots("   ", current))
    }

    @Test
    fun `mergeSnapshots preserves existing values for keys in both`() {
        val existing = """{"A":1}"""
        val current = """{"A":2,"B":1}"""

        val mergedJson = ShellOps.mergeSnapshots(existing, current)
        val resultMap = Json.decodeFromString<Map<String, Int>>(mergedJson)

        assertEquals(mapOf("A" to 1, "B" to 1), resultMap)
    }

    @Test
    fun `mergeSnapshots with malformed existing returns current without crashing`() {
        val current = """{"A":2,"B":1}"""
        val malformed1 = """{not a valid json"""
        val malformed2 = """[1, 2, 3]"""

        assertEquals(current, ShellOps.mergeSnapshots(malformed1, current))
        assertEquals(current, ShellOps.mergeSnapshots(malformed2, current))
    }
}
