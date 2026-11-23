package org.privacyguard.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.privacyguard.android.storage.PackageStorageStats
import org.privacyguard.android.storage.TrimCachesCommandBuilder
import java.util.Locale

internal fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

@Composable
fun SpaceOptimiserScreen(
    uiState: UiState,
    onGrantUsageStatsShizuku: () -> Unit,
    onRefreshStorage: () -> Unit,
    onTrimCaches: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        onRefreshStorage()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        item {
            StorageOverviewCard(
                availableBytes = uiState.availableStorageBytes,
                isLoading = uiState.spaceOptimiserLoading,
                onRefresh = onRefreshStorage
            )
        }

        if (!uiState.hasUsageStatsAccess) {
            item {
                UsageAccessOnboardingCard(
                    isShizukuConnected = uiState.shizukuStatus == ShizukuStatus.CONNECTED,
                    isLoading = uiState.spaceOptimiserLoading,
                    onGrantViaShizuku = onGrantUsageStatsShizuku,
                    context = context
                )
            }
        } else {
            item {
                DeviceWideCacheTrimSection(
                    isShizukuConnected = uiState.shizukuStatus == ShizukuStatus.CONNECTED,
                    isLoading = uiState.spaceOptimiserLoading,
                    lastTrimResult = uiState.lastTrimResult,
                    onTrimCaches = onTrimCaches
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Reported Application Caches (${uiState.packageStorageStats.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (uiState.packageStorageStats.isEmpty() && uiState.spaceOptimiserLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (uiState.packageStorageStats.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No application cache data available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = uiState.packageStorageStats,
                    key = { it.packageName }
                ) { stats ->
                    PackageStorageRow(
                        stats = stats,
                        context = context
                    )
                }
            }
        }
    }
}

@Composable
fun StorageOverviewCard(
    availableBytes: Long,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Available Device Storage",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (availableBytes > 0L) formatStorageBytes(availableBytes) else "Checking...",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onRefresh,
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh storage stats"
                    )
                }
            }
        }
    }
}

@Composable
fun UsageAccessOnboardingCard(
    isShizukuConnected: Boolean,
    isLoading: Boolean,
    onGrantViaShizuku: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Usage Access Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Android requires Usage Access permission to read per-app cache and storage statistics through StorageStatsManager.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onGrantViaShizuku,
                    enabled = isShizukuConnected && !isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Grant via Shizuku", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Throwable) {
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open Settings", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun DeviceWideCacheTrimSection(
    isShizukuConnected: Boolean,
    isLoading: Boolean,
    lastTrimResult: String?,
    onTrimCaches: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedDelta by remember { mutableStateOf(TrimCachesCommandBuilder.DELTA_512_MB) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Device-Wide Cache Trim",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Warning banner (M4-D specification invariant)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device-wide cache trimming requests the Android system to purge temporary cache files across all applications using system LRU policies. Individual application cache selection is not supported at shell privilege on Android 15.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Delta chips: +256 MB, +512 MB, +1 GB
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedDelta == TrimCachesCommandBuilder.DELTA_256_MB,
                    onClick = { selectedDelta = TrimCachesCommandBuilder.DELTA_256_MB },
                    label = { Text("+256 MB", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = selectedDelta == TrimCachesCommandBuilder.DELTA_512_MB,
                    onClick = { selectedDelta = TrimCachesCommandBuilder.DELTA_512_MB },
                    label = { Text("+512 MB", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = selectedDelta == TrimCachesCommandBuilder.DELTA_1_GB,
                    onClick = { selectedDelta = TrimCachesCommandBuilder.DELTA_1_GB },
                    label = { Text("+1 GB", fontSize = 12.sp) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onTrimCaches(selectedDelta) },
                enabled = isShizukuConnected && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Trim Caches (Device-Wide)")
            }

            if (lastTrimResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lastTrimResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun PackageStorageRow(
    stats: PackageStorageStats,
    context: Context,
    modifier: Modifier = Modifier
) {
    val pm = context.packageManager
    val appLabel = remember(stats.packageName) {
        try {
            val appInfo = pm.getApplicationInfo(stats.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Throwable) {
            stats.packageName
        }
    }
    val totalCache = stats.cacheBytes + stats.externalCacheBytes

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stats.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Reported Cache: ${formatStorageBytes(totalCache)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }

            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", stats.packageName, null)
                        }
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("App Info", fontSize = 12.sp)
            }
        }
    }
}
