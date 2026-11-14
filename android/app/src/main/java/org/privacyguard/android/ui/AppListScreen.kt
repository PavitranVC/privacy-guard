package org.privacyguard.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.entity.AppScanResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppListScreen(
    uiState: UiState,
    onSearchQueryChange: (String) -> Unit,
    onFilterChange: (AppFilter) -> Unit,
    onSelectPackage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Search bar
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search by package name...", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    modifier = Modifier.size(20.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Filter chips row
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.activeFilter == AppFilter.ALL,
                onClick = { onFilterChange(AppFilter.ALL) },
                label = { Text("All (${uiState.allApps.size})", fontSize = 12.sp) }
            )
            FilterChip(
                selected = uiState.activeFilter == AppFilter.HIGH_RISK,
                onClick = { onFilterChange(AppFilter.HIGH_RISK) },
                label = { Text("High Risk (≥ 0.70)", fontSize = 12.sp) }
            )
            FilterChip(
                selected = uiState.activeFilter == AppFilter.TRACKERS_DETECTED,
                onClick = { onFilterChange(AppFilter.TRACKERS_DETECTED) },
                label = { Text("Trackers", fontSize = 12.sp) }
            )
            FilterChip(
                selected = uiState.activeFilter == AppFilter.QUARANTINED,
                onClick = { onFilterChange(AppFilter.QUARANTINED) },
                label = { Text("Quarantined", fontSize = 12.sp) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // App list or empty state
        if (uiState.filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.allApps.isEmpty()) {
                        "No apps scanned yet.\nClick 'Scan & Reconcile Now' to begin."
                    } else {
                        "No matching packages found."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.filteredApps,
                    key = { it.pkg }
                ) { app ->
                    AppItemCard(
                        app = app,
                        onClick = { onSelectPackage(app.pkg) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun AppItemCard(
    app: AppScanResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trackers: List<String> = try {
        Json.decodeFromString(ListSerializer(String.serializer()), app.matchedTrackersJson)
    } catch (_: Throwable) {
        emptyList()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = app.pkg,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                RiskScoreBadge(score = app.riskScore)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tracker Count Chip
                val trackerColor = if (trackers.isNotEmpty()) Color(0xFFC62828) else Color(0xFF2E7D32)
                BadgeChip(
                    text = "${trackers.size} Trackers",
                    color = trackerColor
                )

                // Framework Tag
                if (app.detectedFramework.isNotBlank() && app.detectedFramework != "UNKNOWN") {
                    BadgeChip(
                        text = app.detectedFramework,
                        color = Color(0xFF0277BD)
                    )
                }

                // OSINT Verdict Chip
                if (app.osintVerdict.isNotBlank()) {
                    val (verdictText, verdictColor) = when {
                        app.osintVerdict.startsWith("MALICIOUS") -> "MALICIOUS" to Color(0xFFC62828)
                        app.osintVerdict.startsWith("SUSPICIOUS") -> "SUSPICIOUS" to Color(0xFFEF6C00)
                        app.osintVerdict.startsWith("CLEAN") -> "CLEAN" to Color(0xFF2E7D32)
                        app.osintVerdict.startsWith("EXCLUDED") -> "EXCLUDED" to Color(0xFF757575)
                        else -> "PENDING" to Color(0xFF757575)
                    }
                    BadgeChip(text = verdictText, color = verdictColor)
                }
            }
        }
    }
}

@Composable
fun RiskScoreBadge(score: Double) {
    val (bgColor, textColor, label) = when {
        score >= 0.70 -> Triple(Color(0xFFFFCDD2), Color(0xFFB71C1C), "HIGH RISK")
        score >= 0.40 -> Triple(Color(0xFFFFE0B2), Color(0xFFE65100), "MEDIUM")
        else -> Triple(Color(0xFFC8E6C9), Color(0xFF1B5E20), "LOW RISK")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = "$label ${(score * 100).toInt()}%",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BadgeChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
