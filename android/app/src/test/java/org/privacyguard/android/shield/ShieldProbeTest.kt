package org.privacyguard.android.shield

import org.junit.Assert.assertEquals
import org.junit.Test

class ShieldProbeTest {

    @Test
    fun `vpn null on both families returns STALLED`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = null,
            vpnV6 = null,
            physV4 = "192.0.2.1",
            physV6 = "2001:db8::1",
            hasPhysical = true
        )
        assertEquals(TunnelStatus.STALLED, result)
    }

    @Test
    fun `no physical network returns INCONCLUSIVE`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = "198.51.100.1",
            vpnV6 = "2001:db8:vpn::1",
            physV4 = null,
            physV6 = null,
            hasPhysical = false
        )
        assertEquals(TunnelStatus.INCONCLUSIVE, result)
    }

    @Test
    fun `v4 match returns LEAKING`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = "192.0.2.1",
            vpnV6 = null,
            physV4 = "192.0.2.1",
            physV6 = null,
            hasPhysical = true
        )
        assertEquals(TunnelStatus.LEAKING, result)
    }

    @Test
    fun `v6 match returns LEAKING`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = "198.51.100.1",
            vpnV6 = "2001:db8:phys::1",
            physV4 = "192.0.2.1",
            physV6 = "2001:db8:phys::1",
            hasPhysical = true
        )
        assertEquals(TunnelStatus.LEAKING, result)
    }

    @Test
    fun `both families differ returns HEALTHY`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = "198.51.100.1",
            vpnV6 = "2001:db8:vpn::1",
            physV4 = "192.0.2.1",
            physV6 = "2001:db8:phys::1",
            hasPhysical = true
        )
        assertEquals(TunnelStatus.HEALTHY, result)
    }

    @Test
    fun `v4 healthy but v6 leak returns LEAKING because leak takes precedence`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = "198.51.100.1",
            vpnV6 = "2001:db8:leak::1",
            physV4 = "192.0.2.1",
            physV6 = "2001:db8:leak::1",
            hasPhysical = true
        )
        assertEquals(TunnelStatus.LEAKING, result)
    }

    @Test
    fun `vpn has v4 but phys has no v4 and no v6 returns INCONCLUSIVE`() {
        val result = ShieldProbe.classifyTunnel(
            vpnV4 = "198.51.100.1",
            vpnV6 = null,
            physV4 = null,
            physV6 = null,
            hasPhysical = true
        )
        assertEquals(TunnelStatus.INCONCLUSIVE, result)
    }
}
