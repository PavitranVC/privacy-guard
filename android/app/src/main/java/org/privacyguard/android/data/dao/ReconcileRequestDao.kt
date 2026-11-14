package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.privacyguard.android.data.entity.ReconcileRequest

@Dao
interface ReconcileRequestDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(request: ReconcileRequest): Long

    @Query("""
        INSERT INTO reconcile_request (pkg, requestedAt, seq)
        VALUES (:pkg, :now, :seq)
        ON CONFLICT(pkg) DO UPDATE SET
            requestedAt = MAX(requestedAt, :now),
            seq = :seq
    """)
    suspend fun upsertRequestWithSeq(pkg: String, now: Long, seq: Long)

    suspend fun upsertRequest(pkg: String, now: Long) = upsertRequestWithSeq(pkg, now, 0L)

    @Query("SELECT * FROM reconcile_request ORDER BY requestedAt ASC")
    suspend fun getAllPending(): List<ReconcileRequest>

    @Query("DELETE FROM reconcile_request WHERE pkg = :pkg AND seq <= :seenSeq")
    suspend fun deleteProcessed(pkg: String, seenSeq: Long): Int

    @Query("SELECT COUNT(*) FROM reconcile_request")
    suspend fun countPending(): Int

    @Transaction
    suspend fun popBatch(batchSize: Int): List<ReconcileRequest> {
        val pending = getAllPending()
        return pending.take(batchSize)
    }
}
