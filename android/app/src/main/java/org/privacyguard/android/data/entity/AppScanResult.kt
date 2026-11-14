package org.privacyguard.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_scan_result")
data class AppScanResult(
    @PrimaryKey
    val pkg: String,
    val versionCode: Long,
    val certSha256: String,
    val apkSha256: String,
    val scanStatus: Int,
    val riskScore: Double,
    val maxConfidence: Double,
    val matchedTrackersJson: String,
    val dangerousPermissionsJson: String,
    val detectedFramework: String,
    val osintVerdict: String,
    val scannedAt: Long
)
