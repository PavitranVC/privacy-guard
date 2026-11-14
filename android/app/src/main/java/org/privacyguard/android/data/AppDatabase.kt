package org.privacyguard.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.privacyguard.android.data.dao.CapabilityDao
import org.privacyguard.android.data.dao.PolicyOpDao
import org.privacyguard.android.data.dao.ReconcileRequestDao
import org.privacyguard.android.data.dao.ScanFailedDao
import org.privacyguard.android.data.entity.Capability
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.data.entity.ReconcileRequest
import org.privacyguard.android.data.entity.ScanFailed

@Database(
    entities = [
        ReconcileRequest::class,
        PolicyOp::class,
        Capability::class,
        ScanFailed::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reconcileRequestDao(): ReconcileRequestDao
    abstract fun policyOpDao(): PolicyOpDao
    abstract fun capabilityDao(): CapabilityDao
    abstract fun scanFailedDao(): ScanFailedDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

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
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA synchronous = NORMAL")
                    }
                })
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
