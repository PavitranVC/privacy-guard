package org.privacyguard.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey
    val id: Int = 1,
    val bootCount: Int,
    val lastSequenceNumber: Int,
    val updatedAt: Long
)
