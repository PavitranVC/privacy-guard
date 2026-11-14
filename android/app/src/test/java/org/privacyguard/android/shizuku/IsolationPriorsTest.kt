package org.privacyguard.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.service.ShellResult

class IsolationPriorsTest {

    @Test
    fun `test parseStandbyBucketOutput with Part 1 fixture outputs`() {
        assertEquals("10", ShellOps.parseStandbyBucketOutput("10\n"))
        assertEquals("20", ShellOps.parseStandbyBucketOutput("20"))
        assertEquals("30", ShellOps.parseStandbyBucketOutput("30"))
        assertEquals("40", ShellOps.parseStandbyBucketOutput("40\n"))
        assertEquals("45", ShellOps.parseStandbyBucketOutput("45"))
        assertEquals("50", ShellOps.parseStandbyBucketOutput("50"))

        // Word format if returned by some devices
        assertEquals("rare", ShellOps.parseStandbyBucketOutput("rare\n"))

        // Null/empty or error outputs
        assertNull(ShellOps.parseStandbyBucketOutput(null))
        assertNull(ShellOps.parseStandbyBucketOutput(""))
        assertNull(ShellOps.parseStandbyBucketOutput("Error: Unknown bucket: exempted"))
    }

    @Test
    fun `test isSettableBucket validates settable values only`() {
        assertTrue(ShellOps.isSettableBucket("10"))
        assertTrue(ShellOps.isSettableBucket("20"))
        assertTrue(ShellOps.isSettableBucket("30"))
        assertTrue(ShellOps.isSettableBucket("40"))
        assertTrue(ShellOps.isSettableBucket("45"))
        assertTrue(ShellOps.isSettableBucket("50"))
        assertTrue(ShellOps.isSettableBucket("active"))
        assertTrue(ShellOps.isSettableBucket("rare"))
        assertTrue(ShellOps.isSettableBucket("restricted"))
        assertTrue(ShellOps.isSettableBucket("never"))

        // Non-settable values (exempted=5)
        assertFalse(ShellOps.isSettableBucket("5"))
        assertFalse(ShellOps.isSettableBucket("exempted"))
        assertFalse(ShellOps.isSettableBucket(null))
        assertFalse(ShellOps.isSettableBucket(""))
    }

    @Test
    fun `test parsePriors with empty bucket suffix`() {
        val priors = ShellOps.parsePriors("APPOPS_STANDBY_RESTRICTED|bucket=|appops=allow")
        assertNotNull(priors)
        assertNull(priors!!.bucket)
        assertEquals("allow", priors.appOpsMode)
    }

    @Test
    fun `test parseAppOpsRunAnyOutput with Part 1 fixture outputs`() {
        // (a) baseline
        val baselineOut = "No operations.\nDefault mode: allow"
        assertEquals("allow", ShellOps.parseAppOpsRunAnyOutput(baselineOut))

        // (b) ignore
        val ignoreOut = "Uid mode: RUN_ANY_IN_BACKGROUND: ignore"
        assertEquals("ignore", ShellOps.parseAppOpsRunAnyOutput(ignoreOut))

        // (c) allow
        val allowOut = "No operations.\nDefault mode: allow"
        assertEquals("allow", ShellOps.parseAppOpsRunAnyOutput(allowOut))

        // (d) default
        val defaultOut = "Uid mode: RUN_ANY_IN_BACKGROUND: default"
        assertEquals("default", ShellOps.parseAppOpsRunAnyOutput(defaultOut))

        // (e) deny
        val denyOut = "Uid mode: RUN_ANY_IN_BACKGROUND: deny"
        assertEquals("deny", ShellOps.parseAppOpsRunAnyOutput(denyOut))

        // (f) foreground (captured from device)
        val fgOut = "Uid mode: RUN_ANY_IN_BACKGROUND: foreground"
        assertEquals("foreground", ShellOps.parseAppOpsRunAnyOutput(fgOut))

        // Unknown / garbage / empty modes must return null
        assertNull(ShellOps.parseAppOpsRunAnyOutput("Uid mode: RUN_ANY_IN_BACKGROUND: garbage"))
        assertNull(ShellOps.parseAppOpsRunAnyOutput("Uid mode: RUN_ANY_IN_BACKGROUND: invalid_mode"))
        assertNull(ShellOps.parseAppOpsRunAnyOutput(null))
        assertNull(ShellOps.parseAppOpsRunAnyOutput(""))
    }

    @Test
    fun `test isValidAppOpsMode validates allowed modes only`() {
        assertTrue(ShellOps.isValidAppOpsMode("allow"))
        assertTrue(ShellOps.isValidAppOpsMode("ignore"))
        assertTrue(ShellOps.isValidAppOpsMode("deny"))
        assertTrue(ShellOps.isValidAppOpsMode("default"))
        assertTrue(ShellOps.isValidAppOpsMode("foreground"))

        assertFalse(ShellOps.isValidAppOpsMode("garbage"))
        assertFalse(ShellOps.isValidAppOpsMode("active"))
        assertFalse(ShellOps.isValidAppOpsMode(null))
        assertFalse(ShellOps.isValidAppOpsMode(""))
    }

    @Test
    fun `test shouldReusePriors and isLegacyPartialWithoutSuffix decision logic`() {
        val validObserved = "APPOPS_STANDBY_RESTRICTED|bucket=40|appops=ignore"
        val legacyNoSuffix = "APPOPS_STANDBY_RESTRICTED"
        val malformedSuffix = "APPOPS_STANDBY_RESTRICTED|invalid_token"

        // 1. Reuse when observed has priors in PENDING after drift or version reset
        assertTrue(ShellOps.shouldReusePriors(validObserved))
        assertTrue(ShellOps.shouldReusePriors(PolicyOp.STATUS_PENDING, validObserved))
        assertTrue(ShellOps.shouldReusePriors(PolicyOp.STATUS_FAILED_TRANSIENT, validObserved))
        assertTrue(ShellOps.shouldReusePriors(PolicyOp.STATUS_APPLYING, validObserved))

        // 2. Read fresh when observed is null or empty
        assertFalse(ShellOps.shouldReusePriors(null))
        assertFalse(ShellOps.shouldReusePriors(""))
        assertFalse(ShellOps.shouldReusePriors("   "))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_PENDING, null))

        // 3. Legacy no-suffix rows -> MUST NOT reuse, but detected as legacy
        assertFalse(ShellOps.shouldReusePriors(legacyNoSuffix))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_PENDING, legacyNoSuffix))
        assertTrue(ShellOps.isLegacyPartialWithoutSuffix(legacyNoSuffix))
        assertFalse(ShellOps.isLegacyPartialWithoutSuffix(validObserved))
        assertFalse(ShellOps.isLegacyPartialWithoutSuffix(null))

        // 4. Malformed suffix -> not reused
        assertFalse(ShellOps.shouldReusePriors(malformedSuffix))
    }

    @Test
    fun `test formatIsolationResult and parsePriors round-trip`() {
        val priors = ShellOps.PriorIsolationState(bucket = "40", appOpsMode = "ignore")
        val formatted = ShellOps.formatIsolationResult(ShellOps.IsolationResult.APPOPS_PARTIAL, priors)

        assertTrue(formatted.startsWith("APPOPS_STANDBY_RESTRICTED"))
        assertEquals("APPOPS_STANDBY_RESTRICTED|bucket=40|appops=ignore", formatted)

        val parsed = ShellOps.parsePriors(formatted)
        assertNotNull(parsed)
        assertEquals("40", parsed!!.bucket)
        assertEquals("ignore", parsed.appOpsMode)
    }

    @Test
    fun `test legacy observed without suffix returns null priors`() {
        val legacy = "APPOPS_STANDBY_RESTRICTED"
        val parsed = ShellOps.parsePriors(legacy)
        assertNull(parsed)
    }

    @Test
    fun `test malformed observed suffix handled safely without exception`() {
        assertNull(ShellOps.parsePriors(null))
        assertNull(ShellOps.parsePriors(""))
        assertNull(ShellOps.parsePriors("CHAIN3_PACKAGE_NETWORKING"))
        assertNull(ShellOps.parsePriors("APPOPS_STANDBY_RESTRICTED|"))
        assertNull(ShellOps.parsePriors("APPOPS_STANDBY_RESTRICTED|corrupt=true|invalid=1"))

        // Partial suffix
        val partial = ShellOps.parsePriors("APPOPS_STANDBY_RESTRICTED|bucket=10")
        assertNotNull(partial)
        assertEquals("10", partial!!.bucket)
        assertNull(partial.appOpsMode)
    }

    @Test
    fun `test re-apply does not overwrite existing priors`() {
        val originalObserved = "APPOPS_STANDBY_RESTRICTED|bucket=40|appops=ignore"
        val existingPriors = ShellOps.parsePriors(originalObserved)

        assertNotNull(existingPriors)
        assertEquals("40", existingPriors!!.bucket)
        assertEquals("ignore", existingPriors.appOpsMode)

        // If existing priors are present, reformatting reuses them rather than new state
        val reformatted = ShellOps.formatIsolationResult(ShellOps.IsolationResult.APPOPS_PARTIAL, existingPriors)
        assertEquals(originalObserved, reformatted)
    }

    @Test
    fun `test resolveRollbackAppOps and resolveRollbackBucket fallbacks`() {
        // 1. Valid priors resolve exactly
        assertEquals("ignore", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("40", "ignore")))
        assertEquals("deny", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("30", "deny")))
        assertEquals("default", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("20", "default")))
        assertEquals("foreground", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("10", "foreground")))
        assertEquals("allow", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("active", "allow")))

        assertEquals("40", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("40", "ignore")))
        assertEquals("50", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("50", "allow")))
        assertEquals("never", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("never", "allow")))
        assertEquals("restricted", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("restricted", "allow")))

        // 2. Invalid / unknown appops mode -> falls back to "allow"
        assertEquals("allow", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("40", "garbage")))
        assertEquals("allow", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("40", "")))
        assertEquals("allow", ShellOps.resolveRollbackAppOps(ShellOps.PriorIsolationState("40", null)))

        // 3. Unsettable / empty / unknown bucket -> falls back to "active"
        assertEquals("active", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("5", "allow")))
        assertEquals("active", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("exempted", "allow")))
        assertEquals("active", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("garbage", "allow")))
        assertEquals("active", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState("", "allow")))
        assertEquals("active", ShellOps.resolveRollbackBucket(ShellOps.PriorIsolationState(null, "allow")))

        // 4. Legacy (null priors) -> both fall back to OS defaults
        assertEquals("allow", ShellOps.resolveRollbackAppOps(null))
        assertEquals("active", ShellOps.resolveRollbackBucket(null))
    }

    @Test
    fun `test buildIsolationStep3ApplyCommands passes validateCommand`() {
        val applyCmds = ShellOps.buildIsolationStep3ApplyCommands(10355, 0, "com.ansangha.drdriving")
        assertEquals(2, applyCmds.size)
        assertNull(ShizukuShellService.validateCommand(applyCmds[0]))
        assertNull(ShizukuShellService.validateCommand(applyCmds[1]))
    }

    @Test
    fun `test buildIsolationRollbackCommands 113-combination validation matrix`() {
        val buckets = listOf(
            "10", "20", "30", "40", "45", "50",
            "active", "working_set", "frequent", "rare", "restricted", "never",
            "5", "garbage", "", null
        )
        val appOpsModes = listOf(
            "allow", "ignore", "deny", "default", "foreground",
            "garbage", null
        )

        var testedCombinations = 0

        // 112 combinations of bucket x appOpsMode
        for (b in buckets) {
            for (a in appOpsModes) {
                val priors = ShellOps.PriorIsolationState(bucket = b, appOpsMode = a)
                val rollbackCmds = ShellOps.buildIsolationRollbackCommands(priors, 10355, 0, "com.ansangha.drdriving")
                assertEquals(2, rollbackCmds.size)

                val errAppOps = ShizukuShellService.validateCommand(rollbackCmds[0])
                assertNull("cmd[0] failed validation for bucket=$b, appops=$a: $errAppOps", errAppOps)

                val errBucket = ShizukuShellService.validateCommand(rollbackCmds[1])
                assertNull("cmd[1] failed validation for bucket=$b, appops=$a: $errBucket", errBucket)

                // Verify fallback behavior for non-settable buckets
                if (b in setOf("10", "20", "30", "40", "45", "50", "active", "working_set", "frequent", "rare", "restricted", "never")) {
                    assertEquals(b, rollbackCmds[1][5])
                } else {
                    assertEquals("active", rollbackCmds[1][5])
                }

                // Verify fallback behavior for non-valid appops
                if (a in setOf("allow", "ignore", "deny", "default", "foreground")) {
                    assertEquals(a, rollbackCmds[0][6])
                } else {
                    assertEquals("allow", rollbackCmds[0][6])
                }

                testedCombinations++
            }
        }

        // 113th case: legacy (priors == null)
        val legacyCmds = ShellOps.buildIsolationRollbackCommands(null, 10355, 0, "com.ansangha.drdriving")
        assertEquals(2, legacyCmds.size)
        assertNull(ShizukuShellService.validateCommand(legacyCmds[0]))
        assertNull(ShizukuShellService.validateCommand(legacyCmds[1]))
        assertEquals("allow", legacyCmds[0][6])
        assertEquals("active", legacyCmds[1][5])
        testedCombinations++

        assertEquals(113, testedCombinations)
    }

    @Test
    fun `test hostile prior string replaced by fallbacks and raw hostile rejected`() {
        val hostileAppOps = "ignore; rm -rf /"
        val hostileBucket = "../"

        // 1. Raw un-sanitized commands with hostile input MUST be rejected by validateCommand
        val rawHostileAppOpsCmd = arrayOf("cmd", "appops", "set", "--uid", "10355", "RUN_ANY_IN_BACKGROUND", hostileAppOps)
        val rawHostileBucketCmd = arrayOf("am", "set-standby-bucket", "--user", "0", "com.ansangha.drdriving", hostileBucket)

        assertNotNull(ShizukuShellService.validateCommand(rawHostileAppOpsCmd))
        assertNotNull(ShizukuShellService.validateCommand(rawHostileBucketCmd))

        // 2. The builder must sanitize/replace them with safe fallbacks ("allow" and "active")
        val hostilePriors = ShellOps.PriorIsolationState(bucket = hostileBucket, appOpsMode = hostileAppOps)
        val builtCmds = ShellOps.buildIsolationRollbackCommands(hostilePriors, 10355, 0, "com.ansangha.drdriving")

        assertEquals(2, builtCmds.size)
        assertEquals("allow", builtCmds[0][6])
        assertEquals("active", builtCmds[1][5])

        // 3. And the resulting commands MUST pass validateCommand
        assertNull(ShizukuShellService.validateCommand(builtCmds[0]))
        assertNull(ShizukuShellService.validateCommand(builtCmds[1]))
    }

    @Test
    fun `test runIsolationStep3 calls persist before first mutating command`() = runBlocking {
        val priors = ShellOps.PriorIsolationState("40", "allow")
        val formattedStatus = ShellOps.formatIsolationResult(ShellOps.IsolationResult.APPOPS_PARTIAL, priors)
        val events = mutableListOf<String>()

        val onPersist: suspend (String) -> Boolean = { status ->
            events.add("persist:$status")
            true
        }

        val exec: suspend (Array<String>) -> ShellResult = { cmd ->
            events.add("exec:${cmd.joinToString(" ")}")
            ShellResult(0, "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\n", "")
        }

        val outcome = ShellOps.runIsolationStep3(
            priors = priors,
            formattedStatus = formattedStatus,
            appUid = 10355,
            userId = 0,
            pkg = "com.ansangha.drdriving",
            onPersistObserved = onPersist,
            exec = exec
        )

        assertNotNull(outcome)
        assertEquals(ShellOps.IsolationResult.APPOPS_PARTIAL, outcome!!.result)
        val persistIdx = events.indexOf("persist:$formattedStatus")
        val firstMutatingIdx = events.indexOfFirst { it.startsWith("exec:cmd appops set") }
        assertTrue("persist must be called", persistIdx != -1)
        assertTrue("first mutating command must be called", firstMutatingIdx != -1)
        assertTrue("persist must precede first mutating exec: persistIdx=$persistIdx, firstMutatingIdx=$firstMutatingIdx", persistIdx < firstMutatingIdx)
    }

    @Test
    fun `test runIsolationStep3 throws ConcurrentMutationException when persist fails and executes zero mutating commands`() = runBlocking {
        val priors = ShellOps.PriorIsolationState("40", "allow")
        val formattedStatus = ShellOps.formatIsolationResult(ShellOps.IsolationResult.APPOPS_PARTIAL, priors)
        val executedCmds = mutableListOf<String>()

        val onPersist: suspend (String) -> Boolean = { _ -> false }
        val exec: suspend (Array<String>) -> ShellResult = { cmd ->
            executedCmds.add(cmd.joinToString(" "))
            ShellResult(0, "", "")
        }

        var thrown = false
        try {
            ShellOps.runIsolationStep3(
                priors = priors,
                formattedStatus = formattedStatus,
                appUid = 10355,
                userId = 0,
                pkg = "com.ansangha.drdriving",
                onPersistObserved = onPersist,
                exec = exec
            )
        } catch (_: ConcurrentMutationException) {
            thrown = true
        }

        assertTrue("ConcurrentMutationException must be thrown", thrown)
        assertTrue("Zero mutating commands should be executed: $executedCmds", executedCmds.isEmpty())
    }

    @Test
    fun `test runIsolationStep3 runs commands when onPersistObserved is null`() = runBlocking {
        val priors = ShellOps.PriorIsolationState("30", "ignore")
        val formattedStatus = ShellOps.formatIsolationResult(ShellOps.IsolationResult.APPOPS_PARTIAL, priors)
        val executedCmds = mutableListOf<String>()

        val exec: suspend (Array<String>) -> ShellResult = { cmd ->
            executedCmds.add(cmd.joinToString(" "))
            ShellResult(0, "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\n", "")
        }

        val outcome = ShellOps.runIsolationStep3(
            priors = priors,
            formattedStatus = formattedStatus,
            appUid = 10355,
            userId = 0,
            pkg = "com.ansangha.drdriving",
            onPersistObserved = null,
            exec = exec
        )

        assertNotNull(outcome)
        assertEquals(ShellOps.IsolationResult.APPOPS_PARTIAL, outcome!!.result)
        assertEquals(3, executedCmds.size) // apply cmd 1, apply cmd 2, verify cmd
    }

    @Test
    fun `test runIsolationStep3 legacy null priors persists suffix-less status and runs`() = runBlocking {
        val legacyFormattedStatus = ShellOps.formatIsolationResult(ShellOps.IsolationResult.APPOPS_PARTIAL, null)
        assertEquals("APPOPS_STANDBY_RESTRICTED", legacyFormattedStatus)

        var persistedStatus: String? = null
        val executedCmds = mutableListOf<String>()

        val onPersist: suspend (String) -> Boolean = { status ->
            persistedStatus = status
            true
        }
        val exec: suspend (Array<String>) -> ShellResult = { cmd ->
            executedCmds.add(cmd.joinToString(" "))
            ShellResult(0, "Uid mode: RUN_ANY_IN_BACKGROUND: ignore\n", "")
        }

        val outcome = ShellOps.runIsolationStep3(
            priors = null,
            formattedStatus = legacyFormattedStatus,
            appUid = 10355,
            userId = 0,
            pkg = "com.ansangha.drdriving",
            onPersistObserved = onPersist,
            exec = exec
        )

        assertNotNull(outcome)
        assertEquals("APPOPS_STANDBY_RESTRICTED", persistedStatus)
        assertEquals("APPOPS_STANDBY_RESTRICTED", outcome!!.observedString)
        assertEquals(3, executedCmds.size)
    }
}
