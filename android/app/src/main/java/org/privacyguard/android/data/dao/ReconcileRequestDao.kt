package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Query
import org.privacyguard.android.data.entity.ReconcileRequest

@Dao
interface ReconcileRequestDao {

    @Query("""
        INSERT INTO reconcile_request (pkg, requestedAt)
        VALUES (:pkg, :requestedAt)
        ON CONFLICT(pkg) DO UPDATE SET
            requestedAt = max(reconcile_request.requestedAt, :requestedAt)
    """)
    suspend fun upsertRequest(pkg: String, requestedAt: Long)

    @Query("SELECT * FROM reconcile_request ORDER BY requestedAt ASC")
    suspend fun getAllRequests(): List<ReconcileRequest>

    @Query("SELECT * FROM reconcile_request WHERE pkg = :pkg LIMIT 1")
    suspend fun getRequest(pkg: String): ReconcileRequest?

    @Query("SELECT * FROM reconcile_request ORDER BY requestedAt ASC LIMIT :limit")
    suspend fun getNextBatch(limit: Int): List<ReconcileRequest>

    @Query("DELETE FROM reconcile_request WHERE pkg = :pkg AND requestedAt <= :processedAt")
    suspend fun deleteProcessed(pkg: String, processedAt: Long): Int

    @Query("DELETE FROM reconcile_request WHERE pkg = :pkg")
    suspend fun delete(pkg: String): Int

    @Query("DELETE FROM reconcile_request")
    suspend fun clear(): Int
}
