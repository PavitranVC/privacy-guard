package org.privacyguard.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellServiceTest {

    @Test
    fun `test empty command returns error`() {
        val error = ShizukuShellService.validateCommand(emptyArray())
        assertEquals("Empty command", error)
    }

    @Test
    fun `test disallowed binary rejected`() {
        val error = ShizukuShellService.validateCommand(arrayOf("rm", "-rf", "/"))
        assertTrue(error!!.contains("Command prefix not in allowlist"))
    }

    @Test
    fun `test disallowed subcommand on cmd package rejected`() {
        val error = ShizukuShellService.validateCommand(arrayOf("cmd", "package", "uninstall", "com.example.app"))
        assertTrue(error!!.contains("Subcommand not allowed for cmd package"))
    }

    @Test
    fun `test allowed subcommand on cmd package accepted`() {
        val error = ShizukuShellService.validateCommand(arrayOf("cmd", "package", "help"))
        assertNull(error)
    }

    @Test
    fun `test disallowed subcommand on cmd appops rejected`() {
        val error = ShizukuShellService.validateCommand(arrayOf("cmd", "appops", "reset", "com.example.app"))
        assertTrue(error!!.contains("Subcommand not allowed for cmd appops"))
    }

    @Test
    fun `test allowed cmd appops set accepted`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("cmd", "appops", "set", "--uid", "10123", "RUN_ANY_IN_BACKGROUND", "ignore")
        )
        assertNull(error)
    }

    @Test
    fun `test invalid package without dot rejected`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("am", "set-standby-bucket", "--user", "0", "invalidpackage", "restricted")
        )
        assertTrue(error!!.contains("Invalid package or keyword argument"))
    }

    @Test
    fun `test valid package with dot accepted`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("am", "set-standby-bucket", "--user", "0", "com.example.app", "restricted")
        )
        assertNull(error)
    }

    @Test
    fun `test invalid component format rejected`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "0", "com.example/")
        )
        assertTrue(error!!.contains("Invalid component argument"))
    }

    @Test
    fun `test valid component format accepted`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "0", "com.example.app/com.example.app.MyReceiver")
        )
        assertNull(error)
    }
}
