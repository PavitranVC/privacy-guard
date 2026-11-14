package org.privacyguard.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.privacyguard.android.data.dao.AppScanResultDao
import org.privacyguard.android.data.dao.CapabilityDao
import org.privacyguard.android.data.dao.PolicyOpDao
import org.privacyguard.android.data.dao.ReconcileRequestDao
import org.privacyguard.android.data.dao.ScanFailedDao
import org.privacyguard.android.data.dao.SyncStateDao
import org.privacyguard.android.data.entity.AppScanResult
import org.privacyguard.android.data.entity.Capability
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.data.entity.ReconcileRequest
import org.privacyguard.android.data.entity.ScanFailed
import org.privacyguard.android.data.entity.SyncState

@Database(
    entities = [
        ReconcileRequest::class,
        PolicyOp::class,
        Capability::class,
        ScanFailed::class,
        SyncState::class,
        AppScanResult::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reconcileRequestDao(): ReconcileRequestDao
    abstract fun policyOpDao(): PolicyOpDao
    abstract fun capabilityDao(): CapabilityDao
    abstract fun scanFailedDao(): ScanFailedDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun appScanResultDao(): AppScanResultDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add lastCrashAt to scan_failed
                db.execSQL("ALTER TABLE scan_failed ADD COLUMN lastCrashAt INTEGER NOT NULL DEFAULT 0")

                // 2. Remove duplicate rows before creating unique index
                db.execSQL("DELETE FROM policy_op WHERE id NOT IN (SELECT MIN(id) FROM policy_op GROUP BY pkg, op, target)")

                // 3. Add unique index to policy_op for idempotent upserts
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_policy_op_pkg_op_target ON policy_op(pkg, op, target)")

                // 4. Create sync_state table for boot count and sequence tracking
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        bootCount INTEGER NOT NULL,
                        lastSequenceNumber INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add seq column to reconcile_request
                db.execSQL("ALTER TABLE reconcile_request ADD COLUMN seq INTEGER NOT NULL DEFAULT 0")

                // 2. Migrate capability table to composite primary key (op, buildFingerprint)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS capability_new (
                        op TEXT NOT NULL,
                        supported INTEGER NOT NULL,
                        buildFingerprint TEXT NOT NULL,
                        probedAt INTEGER NOT NULL,
                        PRIMARY KEY(op, buildFingerprint)
                    )
                """)
                db.execSQL("""
                    INSERT INTO capability_new (op, supported, buildFingerprint, probedAt)
                    SELECT op, supported, buildFingerprint, probedAt FROM capability
                """)
                db.execSQL("DROP TABLE capability")
                db.execSQL("ALTER TABLE capability_new RENAME TO capability")

                // 3. Create app_scan_result table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_scan_result` (
                        `pkg` TEXT NOT NULL,
                        `versionCode` INTEGER NOT NULL,
                        `certSha256` TEXT NOT NULL,
                        `apkSha256` TEXT NOT NULL,
                        `scanStatus` INTEGER NOT NULL,
                        `riskScore` REAL NOT NULL,
                        `maxConfidence` REAL NOT NULL,
                        `matchedTrackersJson` TEXT NOT NULL,
                        `dangerousPermissionsJson` TEXT NOT NULL,
                        `detectedFramework` TEXT NOT NULL,
                        `osintVerdict` TEXT NOT NULL,
                        `scannedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pkg`)
                    )
                """)
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "privacy_guard.db"
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA synchronous = NORMAL")
                    }
                })
                .build()
        }
    }
}
