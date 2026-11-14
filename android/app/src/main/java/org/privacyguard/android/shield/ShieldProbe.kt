package org.privacyguard.android.shield

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection

enum class TunnelStatus {
    HEALTHY,
    STALLED,
    LEAKING,
    DISCONNECTED,
    NO_VPN,
    INCONCLUSIVE
}

data class ShieldTelemetry(
    val isPrivateDnsActive: Boolean,
    val privateDnsServerName: String?,
    val isVpnActive: Boolean,
    val isAlwaysOnLockdown: Boolean,
    val isSinkholeActive: Boolean,
    val egressIp: String?,
    val warpStatus: String,
    val tunnelStatus: TunnelStatus
)

object ShieldProbe {

    private const val IPV4_TRACE_URL = "https://1.1.1.1/cdn-cgi/trace"
    private const val IPV6_TRACE_URL = "https://[2606:4700:4700::1111]/cdn-cgi/trace"
    private const val CANARY_TRACKER_HOST = "app-measurement.com"

    /**
     * Executes passive telemetry and active dual-socket probes.
     * Strictly uses no VpnService.
     */
    fun checkShield(context: Context): ShieldTelemetry {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return fallbackTelemetry()

        val cr = context.contentResolver
        val activeNetwork = cm.activeNetwork
        if (activeNetwork == null) {
            return fallbackTelemetry()
        }

        val lp = cm.getLinkProperties(activeNetwork)
        val caps = cm.getNetworkCapabilities(activeNetwork)

        val isPrivateDnsActive = lp?.isPrivateDnsActive == true
        val privateDnsServerName = lp?.privateDnsServerName

        val isVpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val isAlwaysOnLockdown = runCatching {
            Settings.Secure.getInt(cr, "always_on_vpn_lockdown", 0) == 1
        }.getOrDefault(false)

        // 1. Canary DNS Sinkhole Probe (verifies if tracking canary domain is sinkholed)
        val isSinkholeActive = probeCanarySinkhole()

        // 2. Single-Pass Dual-Socket Cloudflare Trace Probe (IPv4 & IPv6)
        val vpnTraceV4 = probeTrace(activeNetwork, IPV4_TRACE_URL)
        val vpnTraceV6 = probeTrace(activeNetwork, IPV6_TRACE_URL)
        val vpnV4 = vpnTraceV4?.ip
        val vpnV6 = vpnTraceV6?.ip
        val egressIp = vpnV4 ?: vpnV6
        val warpStatus = vpnTraceV4?.warp ?: vpnTraceV6?.warp ?: "off"

        val tunnelStatus: TunnelStatus
        if (isVpnActive) {
            // Discover physical underlying network via cm.allNetworks (non-SystemApi)
            val physicalNetwork = cm.allNetworks.firstOrNull { net ->
                val netCaps = cm.getNetworkCapabilities(net)
                netCaps != null &&
                        !netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED) &&
                        (netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                         netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                         netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }

            val physTraceV4 = physicalNetwork?.let { probeTrace(it, IPV4_TRACE_URL) }
            val physTraceV6 = physicalNetwork?.let { probeTrace(it, IPV6_TRACE_URL) }
            val physV4 = physTraceV4?.ip
            val physV6 = physTraceV6?.ip

            tunnelStatus = classifyTunnel(
                vpnV4 = vpnV4,
                vpnV6 = vpnV6,
                physV4 = physV4,
                physV6 = physV6,
                hasPhysical = physicalNetwork != null
            )
        } else {
            tunnelStatus = TunnelStatus.NO_VPN
        }

        return ShieldTelemetry(
            isPrivateDnsActive = isPrivateDnsActive,
            privateDnsServerName = privateDnsServerName,
            isVpnActive = isVpnActive,
            isAlwaysOnLockdown = isAlwaysOnLockdown,
            isSinkholeActive = isSinkholeActive,
            egressIp = egressIp,
            warpStatus = warpStatus,
            tunnelStatus = tunnelStatus
        )
    }

    /**
     * Pure classification function for VPN tunnel state given dual-stack IPs.
     */
    fun classifyTunnel(
        vpnV4: String?,
        vpnV6: String?,
        physV4: String?,
        physV6: String?,
        hasPhysical: Boolean
    ): TunnelStatus {
        if (vpnV4 == null && vpnV6 == null) {
            return TunnelStatus.STALLED
        }
        if (!hasPhysical) {
            return TunnelStatus.INCONCLUSIVE
        }
        val isLeaking = (vpnV4 != null && physV4 != null && vpnV4 == physV4) ||
                (vpnV6 != null && physV6 != null && vpnV6 == physV6)
        if (isLeaking) {
            return TunnelStatus.LEAKING
        }
        val isHealthy = (vpnV4 == null || (physV4 != null && vpnV4 != physV4)) &&
                (vpnV6 == null || (physV6 != null && vpnV6 != physV6)) &&
                (vpnV4 != null || vpnV6 != null)
        if (isHealthy) {
            return TunnelStatus.HEALTHY
        }
        return TunnelStatus.INCONCLUSIVE
    }

    private fun probeCanarySinkhole(): Boolean {
        return try {
            val addresses = InetAddress.getAllByName(CANARY_TRACKER_HOST)
            addresses.isNotEmpty() && addresses.all { addr ->
                addr.isAnyLocalAddress ||
                        addr.isLoopbackAddress ||
                        addr.hostAddress == "0.0.0.0" ||
                        addr.hostAddress == "::" ||
                        addr.hostAddress == "0:0:0:0:0:0:0:0" ||
                        addr.hostAddress == "127.0.0.1" ||
                        addr.hostAddress == "::1"
            }
        } catch (_: UnknownHostException) {
            // Check if network is truly up by verifying cloudflare-dns.com resolves
            try {
                val cfAddresses = InetAddress.getAllByName("cloudflare-dns.com")
                cfAddresses.isNotEmpty() // If cloudflare resolves, canary host is truly sinkholed
            } catch (_: Throwable) {
                false // Network is offline or DNS unavailable; not a sinkhole
            }
        } catch (_: Throwable) {
            false
        }
    }

    internal data class TraceResponse(val ip: String?, val warp: String?)

    internal fun probeTrace(network: Network?, urlString: String): TraceResponse? {
        if (network == null) return null
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = network.openConnection(url) as HttpsURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "PrivacyGuard/1.0")
            conn.setRequestProperty("Connection", "close")

            if (conn.responseCode == 200) {
                var ip: String? = null
                var warp: String? = null
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("ip=")) {
                            ip = line.substringAfter("ip=").trim().ifBlank { null }
                        } else if (line.startsWith("warp=")) {
                            warp = line.substringAfter("warp=").trim().ifBlank { null }
                        }
                    }
                }
                TraceResponse(ip, warp)
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun fallbackTelemetry(): ShieldTelemetry {
        return ShieldTelemetry(
            isPrivateDnsActive = false,
            privateDnsServerName = null,
            isVpnActive = false,
            isAlwaysOnLockdown = false,
            isSinkholeActive = false,
            egressIp = null,
            warpStatus = "off",
            tunnelStatus = TunnelStatus.DISCONNECTED
        )
    }
}
