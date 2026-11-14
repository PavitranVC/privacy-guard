package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.privacyguard.android.data.entity.PolicyOp

@Dao
interface PolicyOpDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(policyOp: PolicyOp): Long

    @Query("SELECT * FROM policy_op WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PolicyOp?

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg AND versionCode = :versionCode")
    suspend fun getByPackageAndVersion(pkg: String, versionCode: Long): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg")
    suspend fun getByPackage(pkg: String): List<PolicyOp>

    @Query("SELECT * FROM policy_op WHERE pkg = :pkg ORDER BY id ASC")
    fun observeByPackage(pkg: String): Flow<List<PolicyOp>>

    @Query("SELECT * FROM policy_op ORDER BY id ASC")
    fun observeAll(): Flow<List<PolicyOp>>

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
     * Atomically claims an op for execution by transitioning to APPLYING and incrementing gen and attempts.
     */
    @Query("""
        UPDATE policy_op
        SET status = 'APPLYING',
            gen = gen + 1,
            attempts = attempts + 1,
            nextAt = :leaseUntilMs
        WHERE id = :id 
          AND gen = :expectedGen 
          AND (status IN ('PENDING', 'FAILED_TRANSIENT') OR (status = 'APPLYING' AND nextAt <= :now))
          AND attempts < 5
    """)
    suspend fun claimOp(id: Long, expectedGen: Long, leaseUntilMs: Long, now: Long): Int

    @Query("""
        UPDATE policy_op
        SET snapshot = CASE WHEN snapshot IS NULL OR snapshot = '' OR snapshot = '{}' THEN :snapshot ELSE snapshot END
        WHERE id = :id AND gen = :activeGen AND status = 'APPLYING'
    """)
    suspend fun saveSnapshot(id: Long, activeGen: Long, snapshot: String?): Int

    @Query("""
        UPDATE policy_op
        SET status = 'APPLIED',
            observed = :observed,
            errClass = null,
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markApplied(
        id: Long,
        expectedGen: Long,
        observed: String?
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'APPLIED_PARTIAL',
            observed = :observed,
            errClass = null,
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markAppliedPartial(
        id: Long,
        expectedGen: Long,
        observed: String?
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'FAILED_TRANSIENT',
            errClass = :errClass,
            nextAt = :nextAt,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markFailedTransient(
        id: Long,
        expectedGen: Long,
        errClass: String?,
        nextAt: Long
    ): Int

    @Query("""
        UPDATE policy_op
        SET status = 'FAILED_PERMANENT',
            errClass = :errClass,
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen AND status = 'APPLYING'
    """)
    suspend fun markFailedPermanent(
        id: Long,
        expectedGen: Long,
        errClass: String?
    ): Int

    @Query("SELECT COUNT(*) FROM policy_op WHERE pkg = :pkg AND op = :op AND status = 'ROLLED_BACK_UNSAFE'")
    suspend fun countLocked(pkg: String, op: String): Int

    @Query("""
        UPDATE policy_op
        SET status = 'PENDING',
            gen = gen + 1,
            attempts = 0,
            nextAt = 0
        WHERE id = :id AND gen = :expectedGen AND status IN ('APPLIED', 'APPLIED_PARTIAL', 'DRIFT')
    """)
    suspend fun markDriftPending(id: Long, expectedGen: Long): Int

    @Query("""
        UPDATE policy_op
        SET status = 'ROLLED_BACK_UNSAFE',
            errClass = 'CRASH_WATCHDOG_TRIGGERED',
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen
    """)
    suspend fun markRolledBackUnsafe(id: Long, expectedGen: Long): Int

    @Query("""
        UPDATE policy_op
        SET status = 'SUPERSEDED',
            errClass = 'ROLLED_BACK_BY_USER',
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND gen = :expectedGen
    """)
    suspend fun markSuperseded(id: Long, expectedGen: Long): Int

    @Query("""
        UPDATE policy_op
        SET status = 'SUPERSEDED',
            errClass = 'UNLOCKED_BY_USER',
            nextAt = 0,
            gen = gen + 1
        WHERE pkg = :pkg AND op = :opType AND status = 'ROLLED_BACK_UNSAFE'
    """)
    suspend fun unlockRolledBackUnsafe(pkg: String, opType: String): Int

    @Query("""
        UPDATE policy_op
        SET versionCode = :versionCode,
            lastUpdateTime = :lastUpdateTime,
            status = 'PENDING',
            attempts = 0,
            nextAt = 0,
            gen = gen + 1
        WHERE id = :id AND status != 'ROLLED_BACK_UNSAFE'
    """)
    suspend fun resetForNewVersion(id: Long, versionCode: Long, lastUpdateTime: Long): Int

    @Transaction
    suspend fun upsertPreservingSnapshotAndGen(
        op: PolicyOp,
        userInitiated: Boolean = false,
        packageReplaced: Boolean = false
    ): Long {
        if (countLocked(op.pkg, op.op) > 0) {
            return -1L
        }
        val existing = findExisting(op.pkg, op.op, op.target)
        if (existing == null) {
            return insertIgnore(op)
        }
        if (existing.status == PolicyOp.STATUS_ROLLED_BACK_UNSAFE) {
            return -1L
        }

        // Protect manual user rollbacks from automatic revival on version change or package replace
        if (existing.status == PolicyOp.STATUS_SUPERSEDED &&
            existing.errClass == "ROLLED_BACK_BY_USER" &&
            !userInitiated
        ) {
            return 0L
        }

        val versionChanged = existing.versionCode != op.versionCode
        if (!versionChanged &&
            existing.status in setOf(
                PolicyOp.STATUS_PENDING,
                PolicyOp.STATUS_APPLYING,
                PolicyOp.STATUS_APPLIED,
                PolicyOp.STATUS_APPLIED_PARTIAL
            )
        ) {
            return 0L
        }

        if (versionChanged &&
            existing.status in listOf(
                PolicyOp.STATUS_SUPERSEDED,
                PolicyOp.STATUS_APPLIED,
                PolicyOp.STATUS_APPLIED_PARTIAL,
                PolicyOp.STATUS_DRIFT
            )
        ) {
            resetForNewVersion(existing.id, op.versionCode, op.lastUpdateTime)
            return existing.id
        } else if (!versionChanged && (userInitiated || packageReplaced) &&
            existing.status in setOf(
                PolicyOp.STATUS_SUPERSEDED,
                PolicyOp.STATUS_FAILED_PERMANENT,
                PolicyOp.STATUS_FAILED_TRANSIENT
            )
        ) {
            resetForNewVersion(existing.id, op.versionCode, op.lastUpdateTime)
            return existing.id
        }
        return existing.id
    }
}
