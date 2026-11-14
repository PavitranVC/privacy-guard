package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import org.privacyguard.android.data.entity.PolicyOp

@Dao
interface PolicyOpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(policyOp: PolicyOp): Long

    @Query("SELECT * FROM policy_op WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PolicyOp?

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg AND versionCode = :versionCode")
    suspend fun getByPackageAndVersion(pkg: String, versionCode: Long): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE status = :status ORDER BY nextAt ASC, id ASC")
    suspend fun getByStatus(status: String): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE status IN ('PENDING', 'DRIFT', 'FAILED_TRANSIENT') AND nextAt <= :currentTime ORDER BY nextAt ASC LIMIT :limit")
    suspend fun getRunnableOps(currentTime: Long, limit: Int): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg AND op = :op AND target = :target LIMIT 1")
    suspend fun findExisting(pkg: String, op: String, target: String): PolicyOp?

    /**
     * Optimistic lock update: updates status, observed, errClass, attempts, nextAt, snapshot, and increments gen
     * only when the current database row matches the expected gen.
     */
    @Query("""
        UPDATE policy_op 
        SET status = :newStatus,
            observed = :observed,
            errClass = :errClass,
            attempts = :attempts,
            nextAt = :nextAt,
            snapshot = :snapshot,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen
    """)
    suspend fun updateOptimistic(
        id: Long,
        expectedGen: Long,
        newStatus: String,
        observed: String?,
        errClass: String?,
        attempts: Int,
        nextAt: Long,
        snapshot: String?
    ): Int

    @Update
    suspend fun update(policyOp: PolicyOp): Int

    @Query("DELETE FROM policy_op WHERE pkg = :pkg")
    suspend fun deleteForPackage(pkg: String): Int

    @Transaction
    suspend fun markApplyingIfGenMatches(id: Long, expectedGen: Long): Boolean {
        val updated = updateOptimistic(
            id = id,
            expectedGen = expectedGen,
            newStatus = PolicyOp.STATUS_APPLYING,
            observed = null,
            errClass = null,
            attempts = 0,
            nextAt = 0,
            snapshot = null
        )
        return updated > 0
    }
}
