package org.privacyguard.android.data.entity

import androidx.room.Entity

@Entity(
    tableName = "scan_failed",
    primaryKeys = ["pkg", "versionCode"]
)
data class ScanFailed(
    val pkg: String,
    val versionCode: Long,
    val crashCount: Int
)
