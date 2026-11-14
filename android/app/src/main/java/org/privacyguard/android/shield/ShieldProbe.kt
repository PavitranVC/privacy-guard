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

        // 2. Dual-Socket Cloudflare Trace Probe (IPv4 & IPv6)
        val vpnIpv4 = probeTraceIp(activeNetwork, IPV4_TRACE_URL)
        val vpnIpv6 = probeTraceIp(activeNetwork, IPV6_TRACE_URL)
        val egressIp = vpnIpv4 ?: vpnIpv6
        val warpStatus = probeTraceWarp(activeNetwork, IPV4_TRACE_URL) ?: "off"

        val tunnelStatus: TunnelStatus
        if (isVpnActive) {
            if (vpnIpv4 == null && vpnIpv6 == null) {
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
                    val physIpv4 = probeTraceIp(physicalNetwork, IPV4_TRACE_URL)
                    val physIpv6 = probeTraceIp(physicalNetwork, IPV6_TRACE_URL)

                    val v4Leak = vpnIpv4 != null && physIpv4 != null && vpnIpv4 == physIpv4
                    val v6Leak = vpnIpv6 != null && physIpv6 != null && vpnIpv6 == physIpv6
                    val isLeaking = v4Leak || v6Leak

                    tunnelStatus = if (isLeaking) {
                        TunnelStatus.LEAKING
                    } else {
                        TunnelStatus.HEALTHY
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
            egressIp = egressIp,
            warpStatus = warpStatus,
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

    internal fun probeTraceIp(network: Network, urlString: String): String? {
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
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("ip=")) {
                            return@useLines line.substringAfter("ip=").trim().ifBlank { null }
                        }
                    }
                    null
                }
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

    internal fun probeTraceWarp(network: Network, urlString: String): String? {
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
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("warp=")) {
                            return@useLines line.substringAfter("warp=").trim().ifBlank { null }
                        }
                    }
                    null
                }
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
