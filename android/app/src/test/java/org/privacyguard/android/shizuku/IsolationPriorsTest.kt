package org.privacyguard.android.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.privacyguard.android.data.entity.PolicyOp

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
        assertTrue(ShellOps.isSettableBucket("active"))
        assertTrue(ShellOps.isSettableBucket("rare"))
        assertTrue(ShellOps.isSettableBucket("restricted"))

        // Non-settable values (exempted=5, never=50)
        assertFalse(ShellOps.isSettableBucket("5"))
        assertFalse(ShellOps.isSettableBucket("50"))
        assertFalse(ShellOps.isSettableBucket("exempted"))
        assertFalse(ShellOps.isSettableBucket("never"))
        assertFalse(ShellOps.isSettableBucket(null))
        assertFalse(ShellOps.isSettableBucket(""))
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

        // 1. Fresh PENDING (e.g. after SUPERSEDED, UNLOCKED, DRIFT, or fresh queue) -> MUST NOT reuse (read fresh)
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_PENDING, validObserved))

        // 2. Retry after transient failure or during active apply -> MUST reuse
        assertTrue(ShellOps.shouldReusePriors(PolicyOp.STATUS_FAILED_TRANSIENT, validObserved))
        assertTrue(ShellOps.shouldReusePriors(PolicyOp.STATUS_APPLYING, validObserved))

        // 3. Other statuses (e.g. APPLIED, SUPERSEDED, DRIFT) -> MUST NOT reuse
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_SUPERSEDED, validObserved))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_APPLIED, validObserved))

        // 4. Legacy no-suffix rows -> MUST NOT reuse, but detected as legacy
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_FAILED_TRANSIENT, legacyNoSuffix))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_PENDING, legacyNoSuffix))
        assertTrue(ShellOps.isLegacyPartialWithoutSuffix(legacyNoSuffix))
        assertFalse(ShellOps.isLegacyPartialWithoutSuffix(validObserved))
        assertFalse(ShellOps.isLegacyPartialWithoutSuffix(null))
        assertFalse(ShellOps.isLegacyPartialWithoutSuffix(""))

        // 5. Empty or null observed -> MUST NOT reuse
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_APPLYING, ""))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_APPLYING, null))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_FAILED_TRANSIENT, ""))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_FAILED_TRANSIENT, null))
        assertFalse(ShellOps.shouldReusePriors(PolicyOp.STATUS_PENDING, null))
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
}
