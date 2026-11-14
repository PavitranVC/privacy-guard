package org.privacyguard.android.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "policy_op",
    indices = [
        Index(value = ["pkg", "versionCode"]),
        Index(value = ["status"]),
        Index(value = ["pkg", "op", "target"], unique = true)
    ]
)
data class PolicyOp(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val pkg: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val op: String,
    val target: String,
    val desired: String,
    val observed: String? = null,
    val status: String,
    val errClass: String? = null,
    val attempts: Int = 0,
    val nextAt: Long = 0,
    val gen: Long = 0,
    val snapshot: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPLYING = "APPLYING"
        const val STATUS_APPLIED = "APPLIED"
        const val STATUS_APPLIED_PARTIAL = "APPLIED_PARTIAL"
        const val STATUS_DRIFT = "DRIFT"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
        const val STATUS_FAILED_TRANSIENT = "FAILED_TRANSIENT"
        const val STATUS_FAILED_PERMANENT = "FAILED_PERMANENT"
        const val STATUS_ROLLED_BACK_UNSAFE = "ROLLED_BACK_UNSAFE"
    }
}
