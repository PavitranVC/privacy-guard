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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(requests: List<ReconcileRequest>): List<Long>

    @Query("SELECT * FROM reconcile_request ORDER BY requestedAt ASC")
    suspend fun getAllRequests(): List<ReconcileRequest>

    @Query("SELECT * FROM reconcile_request WHERE pkg = :pkg LIMIT 1")
    suspend fun getRequest(pkg: String): ReconcileRequest?

    @Query("DELETE FROM reconcile_request WHERE pkg = :pkg")
    suspend fun delete(pkg: String): Int

    @Query("DELETE FROM reconcile_request")
    suspend fun clear(): Int

    @Query("SELECT * FROM reconcile_request ORDER BY requestedAt ASC LIMIT :limit")
    suspend fun getNextBatch(limit: Int): List<ReconcileRequest>

    @Transaction
    suspend fun popNextBatch(limit: Int): List<ReconcileRequest> {
        val batch = getNextBatch(limit)
        for (item in batch) {
            delete(item.pkg)
        }
        return batch
    }
}
