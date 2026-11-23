package org.privacyguard.android.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.privacyguard.android.shizuku.ShizukuShellService

class TrimCachesCommandBuilderTest {

    @Test
    fun `test valid target computation`() {
        val free = 84_086_304_768L
        val delta = TrimCachesCommandBuilder.DELTA_512_MB
        val target = TrimCachesCommandBuilder.computeTargetBytes(free, delta)
        assertEquals(84_623_175_680L, target)
    }

    @Test
    fun `test buildCommand array output and compatibility with ShizukuShellService`() {
        val free = 84_086_304_768L
        val delta = TrimCachesCommandBuilder.DELTA_512_MB
        val cmd = TrimCachesCommandBuilder.buildCommand(free, delta)

        assertArrayEquals(arrayOf("pm", "trim-caches", "84623175680"), cmd)

        // ShizukuShellService validation must accept this command cleanly
        val error = ShizukuShellService.validateCommand(cmd)
        assertNull(error)
    }

    @Test
    fun `test presets and compatibility with ShizukuShellService`() {
        for (delta in listOf(
            TrimCachesCommandBuilder.DELTA_256_MB,
            TrimCachesCommandBuilder.DELTA_512_MB,
            TrimCachesCommandBuilder.DELTA_1_GB
        )) {
            val cmd = TrimCachesCommandBuilder.buildCommand(100_000_000L, delta)
            val error = ShizukuShellService.validateCommand(cmd)
            assertNull(error)
        }
    }

    @Test
    fun `test computeFreedBytes`() {
        // Positive space freed
        assertEquals(542_800_000L, TrimCachesCommandBuilder.computeFreedBytes(10_000_000_000L, 10_542_800_000L))

        // Zero space freed
        assertEquals(0L, TrimCachesCommandBuilder.computeFreedBytes(10_000_000_000L, 10_000_000_000L))

        // Negative drift clamped to 0
        assertEquals(0L, TrimCachesCommandBuilder.computeFreedBytes(10_000_000_000L, 9_990_000_000L))
    }

    @Test
    fun `test computeTargetBytes throws on negative currentFreeBytes`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            TrimCachesCommandBuilder.computeTargetBytes(-1L, TrimCachesCommandBuilder.DELTA_512_MB)
        }
        assertEquals("currentFreeBytes must be non-negative, got: -1", ex.message)
    }

    @Test
    fun `test computeTargetBytes throws on zero or negative deltaBytes`() {
        val exZero = assertThrows(IllegalArgumentException::class.java) {
            TrimCachesCommandBuilder.computeTargetBytes(1000L, 0L)
        }
        assertEquals("deltaBytes must be strictly positive, got: 0", exZero.message)

        val exNeg = assertThrows(IllegalArgumentException::class.java) {
            TrimCachesCommandBuilder.computeTargetBytes(1000L, -500L)
        }
        assertEquals("deltaBytes must be strictly positive, got: -500", exNeg.message)
    }

    @Test
    fun `test computeTargetBytes throws on Long overflow`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            TrimCachesCommandBuilder.computeTargetBytes(Long.MAX_VALUE - 100L, 101L)
        }
        assertTrue(ex.message!!.contains("Target bytes overflow Long.MAX_VALUE"))
    }
}
