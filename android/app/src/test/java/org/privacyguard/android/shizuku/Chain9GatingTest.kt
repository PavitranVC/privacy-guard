package org.privacyguard.android.shizuku

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.privacyguard.android.data.dao.CapabilityDao
import org.privacyguard.android.data.entity.Capability

class Chain9GatingTest {

    private class FakeCapabilityDao : CapabilityDao {
        val store = mutableMapOf<String, Capability>()

        override suspend fun getCapability(op: String, fingerprint: String): Capability? = store[op]

        override suspend fun upsert(capability: Capability) {
            store[capability.op] = capability
        }

        override suspend fun upsertAll(capabilities: List<Capability>) {
            capabilities.forEach { store[it.op] = it }
        }

        override suspend fun getAllCapabilities(): List<Capability> = store.values.toList()

        override fun observeAllCapabilities(): Flow<List<Capability>> = flowOf(store.values.toList())

        override suspend fun isSupported(op: String, fingerprint: String): Boolean? {
            val cap = store[op] ?: return null
            return if (cap.buildFingerprint == fingerprint) cap.supported else null
        }
    }

    private val testFingerprint = "motorola/cancunf_g_sysq/cancunf:15/V1TDS35H.83-20-5-14/24971a-330658:user/release-keys"

    @Test
    fun `test checkChain9Capability returns true when unprobed or supported`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        // 1. Unprobed (not in DAO) -> returns true (proceeds)
        assertTrue(ShellOps.checkChain9Capability(fakeDao, testFingerprint))
        assertTrue(ShellOps.checkChain9Capability(null, testFingerprint))

        // 2. Explicitly supported -> returns true
        fakeDao.upsert(Capability(ShellOps.CAPABILITY_CONNECTIVITY_CHAIN9, true, testFingerprint, 1000L))
        assertTrue(ShellOps.checkChain9Capability(fakeDao, testFingerprint))
    }

    @Test
    fun `test checkChain9Capability returns false when recorded unsupported`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        fakeDao.upsert(Capability(ShellOps.CAPABILITY_CONNECTIVITY_CHAIN9, false, testFingerprint, 1000L))
        assertFalse(ShellOps.checkChain9Capability(fakeDao, testFingerprint))
    }

    @Test
    fun `test recordChain9Unsupported saves unsupported capability to DAO`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        ShellOps.recordChain9Unsupported(fakeDao, testFingerprint)

        val cap = fakeDao.getCapability(ShellOps.CAPABILITY_CONNECTIVITY_CHAIN9, testFingerprint)
        assertNotNull(cap)
        assertFalse(cap!!.supported)
        assertEquals(testFingerprint, cap.buildFingerprint)
        assertEquals(ShellOps.CAPABILITY_CONNECTIVITY_CHAIN9, cap.op)
    }

    @Test
    fun `test isUnknownCommand detects unknown command output accurately`() {
        // Device capture: "Unknown command: oem_deny_3"
        assertTrue(ShellOps.isUnknownCommand("", "Unknown command: oem_deny_3\n"))
        assertTrue(ShellOps.isUnknownCommand("Unknown command: oem_deny_3", null))
        assertTrue(ShellOps.isUnknownCommand("unknown command", ""))

        // Other errors or normal outputs should NOT match
        assertFalse(ShellOps.isUnknownCommand("", "Permission Denial: cannot modify firewall"))
        assertFalse(ShellOps.isUnknownCommand("", "java.lang.SecurityException"))
        assertFalse(ShellOps.isUnknownCommand("Success", ""))
        assertFalse(ShellOps.isUnknownCommand(null, null))
        assertFalse(ShellOps.isUnknownCommand("", ""))
    }

    @Test
    fun `test rollback of CHAIN9 when command returns unknown command is treated as success`() {
        // Test rollback logic condition:
        // (r.exitCode == 0 || ShellOps.isUnknownCommand(r.stdout, r.stderr))
        val exitCode = 255
        val stderr = "Unknown command: oem_deny_3\n"
        val stdout = ""

        val reverted = (exitCode == 0) || ShellOps.isUnknownCommand(stdout, stderr)
        assertTrue("Rollback must succeed when remove returns unknown command", reverted)

        // Contrast with a real error (e.g. exit 1 permission denied)
        val permErrorStderr = "java.lang.SecurityException: not allowed"
        val revertedOnPermError = (exitCode == 0) || ShellOps.isUnknownCommand(stdout, permErrorStderr)
        assertFalse("Rollback must fail when remove fails with unexpected error", revertedOnPermError)
    }
}
