package org.privacyguard.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.privacyguard.android.shield.TunnelStatus

@Composable
fun DashboardCard(
    uiState: UiState,
    onRequestShizukuPermission: () -> Unit,
    onReprobeShield: () -> Unit,
    onTriggerScan: () -> Unit,
    onDismissStatusMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header: App title + Shizuku Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Privacy Guard",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ShizukuBadge(
                    status = uiState.shizukuStatus,
                    onRequestPermission = onRequestShizukuPermission
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Shield Telemetry Banner
            ShieldTelemetrySection(
                telemetry = uiState.shieldStatus,
                onReprobe = onReprobeShield
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action Row: Scan & Reconcile Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTriggerScan,
                    enabled = !uiState.isScanning,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reconciling...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan & Reconcile Now")
                    }
                }
            }

            // Status message toast/banner
            AnimatedVisibility(visible = uiState.statusMessage != null) {
                uiState.statusMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissStatusMessage,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShizukuBadge(
    status: ShizukuStatus,
    onRequestPermission: () -> Unit
) {
    when (status) {
        ShizukuStatus.CONNECTED -> {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2E7D32).copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Shell UID 2000",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2E7D32)
                )
            }
        }
        ShizukuStatus.PERMISSION_REQUIRED -> {
            OutlinedButton(
                onClick = onRequestPermission,
                modifier = Modifier.height(30.dp)
            ) {
                Text("Grant Shizuku", fontSize = 11.sp)
            }
        }
        ShizukuStatus.UNAVAILABLE -> {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFC62828).copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFC62828),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Shizuku Offline",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun ShieldTelemetrySection(
    telemetry: org.privacyguard.android.shield.ShieldTelemetry,
    onReprobe: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shield Telemetry (No VpnService)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(
                    onClick = onReprobe,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Re-probe Shield",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Private DNS row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Private DNS:", style = MaterialTheme.typography.bodySmall)
                val dnsText = if (telemetry.isPrivateDnsActive) {
                    telemetry.privateDnsServerName ?: "Active"
                } else {
                    "Inactive (Plaintext DNS)"
                }
                val dnsColor = if (telemetry.isPrivateDnsActive) Color(0xFF2E7D32) else Color(0xFFE65100)
                Text(
                    text = dnsText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = dnsColor
                )
            }

            // Canary DNS Sinkhole row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Canary Sinkhole:", style = MaterialTheme.typography.bodySmall)
                val sinkholeText = if (telemetry.isSinkholeActive) "VERIFIED (Blocked)" else "UNPROTECTED"
                val sinkholeColor = if (telemetry.isSinkholeActive) Color(0xFF2E7D32) else Color(0xFFC62828)
                Text(
                    text = sinkholeText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = sinkholeColor
                )
            }

            // Tunnel status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("VPN Dual-Socket Probe:", style = MaterialTheme.typography.bodySmall)
                val (tunnelText, tunnelColor) = when (telemetry.tunnelStatus) {
                    TunnelStatus.HEALTHY -> "Healthy Tunnel" to Color(0xFF2E7D32)
                    TunnelStatus.LEAKING -> "TUNNEL LEAK DETECTED" to Color(0xFFC62828)
                    TunnelStatus.STALLED -> "STALLED TUNNEL" to Color(0xFFC62828)
                    TunnelStatus.DISCONNECTED -> "Disconnected" to Color(0xFF757575)
                    TunnelStatus.NO_VPN -> "Direct Connection" to Color(0xFF757575)
                }
                Text(
                    text = tunnelText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = tunnelColor
                )
            }
        }
    }
}
