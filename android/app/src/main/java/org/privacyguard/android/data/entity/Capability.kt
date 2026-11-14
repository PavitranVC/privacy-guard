package org.privacyguard.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "capability")
data class Capability(
    @PrimaryKey
    val op: String,
    val supported: Boolean,
    val buildFingerprint: String,
    val probedAt: Long
)
