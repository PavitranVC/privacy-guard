package org.privacyguard.android.shizuku

import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult

class PrivilegeBackendTest {

    private class FakeBackend(
        override val id: String = "fake",
        private val handler: (Array<String>) -> ShellResult
    ) : PrivilegeBackend {
        override fun exec(cmd: Array<String>): ShellResult = handler(cmd)
    }

    @Test
    fun `test exec passthrough returns backend ShellResult`() {
        val expected = ShellResult(0, "success output\n", "")
        var capturedCmd: Array<String>? = null

        val backend = FakeBackend { cmd ->
            capturedCmd = cmd
            expected
        }

        val result = backend.exec(arrayOf("echo", "hello"))
        assertEquals(expected, result)
        assertEquals("echo hello", capturedCmd?.joinToString(" "))
        assertEquals("fake", backend.id)
    }

    @Test
    fun `test ShizukuBackend delegates to IShellService`() {
        var called = false
        val fakeShellService = object : IShellService {
            override fun exec(cmd: Array<out String>?): ShellResult {
                called = true
                assertEquals("test", cmd?.get(0))
                return ShellResult(0, "shizuku ok", "")
            }
            override fun destroy() {}
            override fun asBinder() = null
        }

        val backend = ShizukuBackend(fakeShellService)
        assertEquals("shizuku", backend.id)
        val result = backend.exec(arrayOf("test"))
        assertEquals(0, result.exitCode)
        assertEquals("shizuku ok", result.stdout)
        assertEquals(true, called)
    }

    @Test
    fun `test backend throwing RemoteException propagates out of killProcessSafely`() {
        val backend = FakeBackend { _ ->
            throw RemoteException("Binder transaction failed")
        }

        try {
            ShellOps.killProcessSafely(backend, "com.ansangha.drdriving", 0)
            fail("RemoteException expected to propagate")
        } catch (e: RemoteException) {
            // In Android unit test stub jar, e.message returns null, but the exception itself propagates
            assertNotNull(e)
        }
    }
}
