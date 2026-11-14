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

class ComponentStateGatingTest {

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

    private val fingerprint = "motorola/cancunf_g_sysq/cancunf:15/V1TDS35H.83-20-5-14/24971a-330658:user/release-keys"

    @Test
    fun `test gating proceeds when capability is unprobed or supported`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        // Unprobed capability (null) -> proceeds (returns true)
        val unprobed = ShellOps.checkComponentStateCapability(fakeDao, fingerprint)
        assertTrue(unprobed)

        // Explicitly supported capability -> proceeds (returns true)
        fakeDao.upsert(Capability(ShellOps.CAPABILITY_PM_COMPONENT_STATE, true, fingerprint, 1000L))
        val supported = ShellOps.checkComponentStateCapability(fakeDao, fingerprint)
        assertTrue(supported)
    }

    @Test
    fun `test gating skips immediately when capability is recorded unsupported`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        // Recorded as unsupported for this fingerprint -> skips (returns false)
        fakeDao.upsert(Capability(ShellOps.CAPABILITY_PM_COMPONENT_STATE, false, fingerprint, 1000L))
        val supported = ShellOps.checkComponentStateCapability(fakeDao, fingerprint)
        assertFalse(supported)
    }

    @Test
    fun `test exact SecurityException text detects shell component state denial`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        val denialEx = SecurityException(
            "Exception occurred while executing 'disable-user':\n" +
            "java.lang.SecurityException: Shell cannot change component state for ComponentInfo{com.ansangha.drdriving/com.google.android.gms.measurement.AppMeasurementJobService} to 3"
        )
        assertTrue(ShellOps.isComponentStateDeniedException(denialEx))

        // On exact denial: record capability
        ShellOps.recordComponentStateDenial(fakeDao, fingerprint)
        val cap = fakeDao.getCapability(ShellOps.CAPABILITY_PM_COMPONENT_STATE, fingerprint)
        assertNotNull(cap)
        assertFalse(cap!!.supported)
        assertEquals(fingerprint, cap.buildFingerprint)
    }

    @Test
    fun `test different SecurityException text does not record component state denial`() = runBlocking {
        val fakeDao = FakeCapabilityDao()

        val otherEx = SecurityException(
            "java.lang.SecurityException: Permission Denial: requires android.permission.INTERACT_ACROSS_USERS"
        )
        assertFalse(ShellOps.isComponentStateDeniedException(otherEx))

        // Different SecurityException does NOT record the capability
        if (ShellOps.isComponentStateDeniedException(otherEx)) {
            ShellOps.recordComponentStateDenial(fakeDao, fingerprint)
        }
        val cap = fakeDao.getCapability(ShellOps.CAPABILITY_PM_COMPONENT_STATE, fingerprint)
        assertNull(cap)
    }
}
