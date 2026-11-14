package org.privacyguard.android.data.entity

import androidx.room.Entity

@Entity(
    tableName = "capability",
    primaryKeys = ["op", "buildFingerprint"]
)
data class Capability(
    val op: String,
    val supported: Boolean,
    val buildFingerprint: String,
    val probedAt: Long
)
