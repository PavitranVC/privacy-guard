package org.privacyguard.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.entity.AppScanResult
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.shizuku.ShellOps

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppDetailScreen(
    app: AppScanResult,
    ops: List<PolicyOp>,
    hasUnsafeRollback: Boolean,
    isProtected: Boolean,
    onBack: () -> Unit,
    onDisableTrackers: (String) -> Unit,
    onRestrictBackground: (String) -> Unit,
    onRollback: (String) -> Unit,
    onUnlockAndRetry: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var showUnlockDialog by remember { mutableStateOf(false) }

    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            title = { Text("Override Crash Protection?") },
            text = {
                Text(
                    "This operation was previously rolled back because the target app crashed within 60 seconds of applying policy. " +
                    "Retrying may cause ${app.pkg} to crash again.\n\nDo you want to unlock and re-apply this operation?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnlockDialog = false
                        onUnlockAndRetry(app.pkg, ShellOps.OP_DISABLE_COMPONENTS)
                    }
                ) {
                    Text("Unlock & Re-apply", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    val trackers: List<String> = try {
        Json.decodeFromString(ListSerializer(String.serializer()), app.matchedTrackersJson)
    } catch (_: Throwable) {
        emptyList()
    }

    val permissions: List<String> = try {
        Json.decodeFromString(ListSerializer(String.serializer()), app.dangerousPermissionsJson)
    } catch (_: Throwable) {
        emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = app.pkg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Unsafe CrashWatchdog Rollback Alert Banner
            if (hasUnsafeRollback) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFC62828),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "CrashWatchdog Auto-Restoration",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC62828)
                                )
                                Text(
                                    text = "Component modifications caused process crash (exit code 4-7) within 60s. Component state was safely restored to prevent crashes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB71C1C)
                                )
                            }
                        }
                        Button(
                            onClick = { showUnlockDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Unlock & Retry")
                        }
                    }
                }
            }

            // Protected System Package Banner
            if (isProtected) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Critical system package components are safe-listed from modification to preserve OS stability.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1B5E20)
                        )
                    }
                }
            }

            // Metrics & Triage Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Static & ML Triage Assessment",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Risk Score:", style = MaterialTheme.typography.bodySmall)
                        RiskScoreBadge(score = app.riskScore)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ML Confidence:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${(app.maxConfidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Framework:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            app.detectedFramework,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("OSINT Verdict:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            app.osintVerdict,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Signing Cert SHA-256:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = app.certSha256.ifBlank { "N/A" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Interactive Policy Controls Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Interactive Tracker Eradication Controls",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Disable Tracker Components Button
                    Button(
                        onClick = { onDisableTrackers(app.pkg) },
                        enabled = !isProtected && !hasUnsafeRollback && trackers.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        Text("Disable ${trackers.size} Tracker Components")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Restrict Background Networking Button
                    Button(
                        onClick = { onRestrictBackground(app.pkg) },
                        enabled = !isProtected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0277BD))
                    ) {
                        Text("Restrict Background & Wi-Fi Networking")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Rollback / Restore Button
                    OutlinedButton(
                        onClick = { onRollback(app.pkg) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restore Default State / Rollback")
                    }
                }
            }

            // Detected Trackers Section
            if (trackers.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Detected Tracker Signatures (${trackers.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            trackers.forEach { tracker ->
                                BadgeChip(text = tracker, color = Color(0xFFC62828))
                            }
                        }
                    }
                }
            }

            // Dangerous Permissions Section
            if (permissions.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Dangerous Permissions (${permissions.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            permissions.forEach { perm ->
                                val shortName = perm.removePrefix("android.permission.")
                                BadgeChip(text = shortName, color = Color(0xFFE65100))
                            }
                        }
                    }
                }
            }

            // Policy Operations History
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Active Policy Operations (${ops.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (ops.isEmpty()) {
                        Text(
                            text = "No policy operations applied to this package.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ops.forEachIndexed { index, op ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${op.op} -> ${op.target}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    op.errClass?.let { err ->
                                        Text(
                                            text = err,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontSize = 10.sp,
                                            color = Color(0xFFC62828)
                                        )
                                    }
                                }
                                PolicyStatusBadge(status = op.status)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PolicyStatusBadge(status: String) {
    val (color, text) = when (status) {
        PolicyOp.STATUS_APPLIED -> Color(0xFF2E7D32) to "APPLIED"
        PolicyOp.STATUS_APPLIED_PARTIAL -> Color(0xFF0277BD) to "PARTIAL"
        PolicyOp.STATUS_PENDING -> Color(0xFFE65100) to "PENDING"
        PolicyOp.STATUS_APPLYING -> Color(0xFF1565C0) to "APPLYING"
        PolicyOp.STATUS_DRIFT -> Color(0xFFF57C00) to "DRIFT"
        PolicyOp.STATUS_ROLLED_BACK_UNSAFE -> Color(0xFFC62828) to "UNSAFE_ROLLBACK"
        PolicyOp.STATUS_SUPERSEDED -> Color(0xFF757575) to "SUPERSEDED"
        else -> Color(0xFFC62828) to status
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
