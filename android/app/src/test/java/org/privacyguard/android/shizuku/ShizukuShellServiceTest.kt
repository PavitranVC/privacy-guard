package org.privacyguard.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

        val errorNever = ShizukuShellService.validateCommand(
            arrayOf("am", "set-standby-bucket", "--user", "0", "com.example.app", "never")
        )
        assertNull(errorNever)
    }

    @Test
    fun `test invalid component format rejected`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "0", "com.example/")
        )
        assertTrue(error!!.contains("Invalid component-only pm syntax"))

        val errorNonPm = ShizukuShellService.validateCommand(
            arrayOf("cmd", "appops", "set", "--uid", "0", "com.example/", "ignore")
        )
        assertTrue(errorNonPm!!.contains("Invalid component argument"))
    }

    @Test
    fun `test valid component format accepted`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "0", "com.example.app/com.example.app.MyReceiver")
        )
        assertNull(error)
    }

    @Test
    fun `test pm disable-user without component slash rejected`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "0", "com.example.app")
        )
        assertTrue(error!!.contains("Invalid component-only pm syntax"))
    }

    @Test
    fun `test pm disable missing user argument rejected`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("pm", "disable", "com.example.app/com.example.MyReceiver")
        )
        assertTrue(error!!.contains("Invalid component-only pm syntax"))
    }

    @Test
    fun `test rollback commands accepted by allowlist`() {
        // 1. Chain 3 reversal (only re-enables package networking; does not toggle global chain3)
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("cmd", "connectivity", "set-package-networking-enabled", "true", "com.example.app")
        ))

        // 2. Chain 9 reversal
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("cmd", "connectivity", "oem_deny_3", "remove", "10123")
        ))

        // 3. AppOps & standby bucket reversal
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("cmd", "appops", "set", "--uid", "10123", "RUN_ANY_IN_BACKGROUND", "allow")
        ))
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("am", "set-standby-bucket", "--user", "0", "com.example.app", "active")
        ))

        // 4. PM default-state restoration
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "default-state", "--user", "0", "com.example.app/com.example.app.MyReceiver")
        ))
    }

    @Test
    fun `test negative pm command syntax validations`() {
        // Whole-package commands rejected
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "disable", "--user", "0", "com.foo.bar")
        ))
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "enable", "--user", "0", "com.foo.bar")
        ))
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "default-state", "--user", "0", "com.foo.bar")
        ))

        // Invalid user IDs (non-digit, Unicode non-ASCII digit)
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "abc", "com.foo.bar/com.foo.bar.Rcv")
        ))
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "٠", "com.foo.bar/com.foo.bar.Rcv")
        ))

        // Combined flag rejected
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user=0", "com.foo.bar/com.foo.bar.Rcv")
        ))

        // Extra tokens (6 tokens) rejected
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "disable-user", "--user", "0", "com.foo.bar/com.foo.bar.Rcv", "extra")
        ))
    }

    @Test
    fun `test am get-standby-bucket validations`() {
        // Valid get-standby-bucket command
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("am", "get-standby-bucket", "--user", "0", "com.example.app")
        ))

        // Missing package argument
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("am", "get-standby-bucket", "--user", "0")
        ))

        // Non-digit user ID
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("am", "get-standby-bucket", "--user", "xyz", "com.example.app")
        ))

        // Invalid package without dot
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("am", "get-standby-bucket", "--user", "0", "invalidpkg")
        ))

        // Extra trailing tokens
        assertNotNull(ShizukuShellService.validateCommand(
            arrayOf("am", "get-standby-bucket", "--user", "0", "com.example.app", "extra")
        ))
    }

    @Test
    fun `test cmd appops set with deny mode accepted`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("cmd", "appops", "set", "--uid", "10355", "RUN_ANY_IN_BACKGROUND", "deny")
        )
        assertNull(error)
    }

    @Test
    fun `test cmd appops set with garbage mode rejected`() {
        val error = ShizukuShellService.validateCommand(
            arrayOf("cmd", "appops", "set", "--uid", "10355", "RUN_ANY_IN_BACKGROUND", "garbage")
        )
        assertNotNull(error)
        assertTrue(error!!.contains("garbage"))
    }

    @Test
    fun `test pm trim-caches validations`() {
        // Valid calls
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "84623175680")
        ))
        assertNull(ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "1048576")
        ))

        // Missing argument (size == 2)
        val errMissing = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches")
        )
        assertNotNull(errMissing)
        assertTrue(errMissing!!.contains("Invalid pm trim-caches syntax"))

        // Empty argument
        val errEmpty = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "")
        )
        assertNotNull(errEmpty)
        assertTrue(errEmpty!!.contains("Invalid pm trim-caches syntax"))

        // Zero
        val errZero = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "0")
        )
        assertNotNull(errZero)
        assertTrue(errZero!!.contains("must be positive Long"))

        // Negative
        val errNeg = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "-100")
        )
        assertNotNull(errNeg)
        assertTrue(errNeg!!.contains("Invalid pm trim-caches syntax"))

        // Unit suffix (non-digits like 500M)
        val errSuffix = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "500M")
        )
        assertNotNull(errSuffix)
        assertTrue(errSuffix!!.contains("Invalid pm trim-caches syntax"))

        // Shell metacharacters
        val errMeta = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "100;id")
        )
        assertNotNull(errMeta)
        assertTrue(errMeta!!.contains("Invalid pm trim-caches syntax"))

        // Overflow
        val errOverflow = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "99999999999999999999999")
        )
        assertNotNull(errOverflow)
        assertTrue(errOverflow!!.contains("must be positive Long"))

        // Extra trailing arguments
        val errExtra = ShizukuShellService.validateCommand(
            arrayOf("pm", "trim-caches", "1048576", "extra")
        )
        assertNotNull(errExtra)
        assertTrue(errExtra!!.contains("Invalid pm trim-caches syntax"))
    }
}
