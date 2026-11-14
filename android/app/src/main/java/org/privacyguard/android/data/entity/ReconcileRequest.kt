package org.privacyguard.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reconcile_request")
data class ReconcileRequest(
    @PrimaryKey
    val pkg: String,
    val requestedAt: Long
)
