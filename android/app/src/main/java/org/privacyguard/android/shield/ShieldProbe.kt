package org.privacyguard.android.shield

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

enum class TunnelStatus {
    HEALTHY,
    STALLED,
    LEAKING,
    DISCONNECTED,
    NO_VPN
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

    private const val CLOUDFLARE_TRACE_URL = "https://cloudflare-dns.com/cdn-cgi/trace"
    private const val CANARY_TRACKER_HOST = "app-measurement.com"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

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
        val isAlwaysOnLockdown = try {
            Settings.Secure.getInt(cr, "always_on_vpn_lockdown", 0) == 1
        } catch (_: Throwable) {
            false
        }

        // 1. Canary DNS Sinkhole Probe (verifies if tracking canary domain is sinkholed)
        val isSinkholeActive = probeCanarySinkhole()

        // 2. Dual-Socket Cloudflare Trace Probe
        val activeTrace = fetchTrace(activeNetwork)

        val tunnelStatus: TunnelStatus
        if (isVpnActive) {
            if (activeTrace == null) {
                // VPN transport is up but active HTTP socket times out
                tunnelStatus = TunnelStatus.STALLED
            } else {
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

                if (physicalNetwork != null) {
                    val physicalTrace = fetchTrace(physicalNetwork)
                    if (physicalTrace != null && physicalTrace.ip != null && physicalTrace.ip == activeTrace.ip) {
                        // Equal egress IPs between VPN and physical network indicates a tunnel leak
                        tunnelStatus = TunnelStatus.LEAKING
                    } else {
                        tunnelStatus = TunnelStatus.HEALTHY
                    }
                } else {
                    tunnelStatus = TunnelStatus.HEALTHY
                }
            }
        } else {
            tunnelStatus = TunnelStatus.NO_VPN
        }

        return ShieldTelemetry(
            isPrivateDnsActive = isPrivateDnsActive,
            privateDnsServerName = privateDnsServerName,
            isVpnActive = isVpnActive,
            isAlwaysOnLockdown = isAlwaysOnLockdown,
            isSinkholeActive = isSinkholeActive,
            egressIp = activeTrace?.ip,
            warpStatus = activeTrace?.warp ?: "off",
            tunnelStatus = tunnelStatus
        )
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

    private fun fetchTrace(network: Network): CloudflareTrace? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(CLOUDFLARE_TRACE_URL)
            conn = network.openConnection(url) as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "PrivacyGuard/1.0")
            conn.setRequestProperty("Connection", "close")

            if (conn.responseCode == 200) {
                val content = conn.inputStream.bufferedReader().use { it.readText() }
                parseTrace(content)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun parseTrace(raw: String): CloudflareTrace {
        var ip: String? = null
        var warp = "off"
        var dns = "off"

        raw.lineSequence().forEach { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                when (parts[0].trim()) {
                    "ip" -> ip = parts[1].trim()
                    "warp" -> warp = parts[1].trim()
                    "dns" -> dns = parts[1].trim()
                }
            }
        }
        return CloudflareTrace(ip = ip, warp = warp, dns = dns)
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

    private data class CloudflareTrace(
        val ip: String? = null,
        val warp: String = "off",
        val dns: String = "off"
    )
}
