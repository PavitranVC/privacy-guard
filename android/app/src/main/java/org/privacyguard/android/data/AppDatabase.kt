package org.privacyguard.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.privacyguard.android.data.dao.CapabilityDao
import org.privacyguard.android.data.dao.PolicyOpDao
import org.privacyguard.android.data.dao.ReconcileRequestDao
import org.privacyguard.android.data.dao.ScanFailedDao
import org.privacyguard.android.data.dao.SyncStateDao
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
        SyncState::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reconcileRequestDao(): ReconcileRequestDao
    abstract fun policyOpDao(): PolicyOpDao
    abstract fun capabilityDao(): CapabilityDao
    abstract fun scanFailedDao(): ScanFailedDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add lastCrashAt to scan_failed
                db.execSQL("ALTER TABLE scan_failed ADD COLUMN lastCrashAt INTEGER NOT NULL DEFAULT 0")

                // 2. Add unique index to policy_op for idempotent upserts
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_policy_op_pkg_op_target ON policy_op(pkg, op, target)")

                // 3. Create sync_state table for boot count and sequence tracking
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
                .addMigrations(MIGRATION_1_2)
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
