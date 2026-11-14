package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.privacyguard.android.data.entity.PolicyOp

@Dao
interface PolicyOpDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(policyOp: PolicyOp): Long

    @Query("SELECT * FROM policy_op WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PolicyOp?

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg AND versionCode = :versionCode")
    suspend fun getByPackageAndVersion(pkg: String, versionCode: Long): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE status = :status ORDER BY nextAt ASC, id ASC")
    suspend fun getByStatus(status: String): List<PolicyOp>

    @Query("""
        SELECT * FROM policy_op 
        WHERE ((status IN ('PENDING', 'FAILED_TRANSIENT') AND nextAt <= :now)
           OR (status = 'APPLYING' AND nextAt <= :now))
          AND attempts < 5
        ORDER BY nextAt ASC LIMIT :limit
    """)
    suspend fun getRunnableOps(now: Long, limit: Int): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg AND op = :op AND target = :target LIMIT 1")
    suspend fun findExisting(pkg: String, op: String, target: String): PolicyOp?

    /**
     * Atomically claims an op for execution by transitioning to APPLYING and bumping gen,
     * without nulling out existing snapshot or observed values.
     */
    @Query("""
        UPDATE policy_op
        SET status = 'APPLYING',
            gen = gen + 1,
            nextAt = :leaseUntilMs
        WHERE id = :id 
          AND gen = :expectedGen 
          AND status IN ('PENDING', 'FAILED_TRANSIENT')
    """)
    suspend fun claimOp(id: Long, expectedGen: Long, leaseUntilMs: Long): Int

    @Query("""
        UPDATE policy_op
        SET status = 'APPLIED',
            observed = :observed,
            snapshot = coalesce(:snapshot, snapshot),
            errClass = null,
            nextAt = 0,
            gen = gen + 1,
            attempts = attempts + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markApplied(
        id: Long,
        expectedGen: Long,
        observed: String?,
        snapshot: String?
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'FAILED_TRANSIENT',
            errClass = :errClass,
            nextAt = :nextAt,
            gen = gen + 1,
            attempts = attempts + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markFailedTransient(
        id: Long,
        expectedGen: Long,
        errClass: String,
        nextAt: Long
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'FAILED_PERMANENT',
            errClass = :errClass,
            observed = :observed,
            nextAt = 0,
            gen = gen + 1,
            attempts = attempts + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markFailedPermanent(
        id: Long,
        expectedGen: Long,
        errClass: String,
        observed: String?
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'SUPERSEDED',
            errClass = 'PACKAGE_DRIFT',
            observed = :observed,
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen
    """)
    suspend fun markDriftSuperseded(
        id: Long,
        expectedGen: Long,
        observed: String?
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'ROLLED_BACK_UNSAFE',
            errClass = 'CRASH_60S_POST_APPLY',
            observed = :observed,
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen
    """)
    suspend fun markRolledBackUnsafe(
        id: Long,
        expectedGen: Long,
        observed: String?
    ): Int

    @Query("DELETE FROM policy_op WHERE pkg = :pkg")
    suspend fun deleteForPackage(pkg: String): Int

    @Transaction
    suspend fun upsertPreservingSnapshotAndGen(op: PolicyOp): Long {
        val existing = findExisting(op.pkg, op.op, op.target)
        return if (existing != null) {
            // Update version and update time if changed, while preserving gen, snapshot, and active status
            existing.id
        } else {
            insertIgnore(op)
        }
    }
}
