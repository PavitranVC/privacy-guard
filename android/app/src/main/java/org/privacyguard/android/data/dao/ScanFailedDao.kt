package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Query
import org.privacyguard.android.data.entity.ScanFailed

@Dao
interface ScanFailedDao {

    @Query("""
        INSERT INTO scan_failed (pkg, versionCode, crashCount, lastCrashAt)
        VALUES (:pkg, :versionCode, 1, :now)
        ON CONFLICT(pkg, versionCode) DO UPDATE SET
            crashCount = crashCount + 1,
            lastCrashAt = :now
    """)
    suspend fun recordCrashAtomic(pkg: String, versionCode: Long, now: Long)

    @Query("SELECT crashCount FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode LIMIT 1")
    suspend fun getCrashCount(pkg: String, versionCode: Long): Int?

    @Query("SELECT * FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode LIMIT 1")
    suspend fun getScanFailed(pkg: String, versionCode: Long): ScanFailed?

    @Query("DELETE FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode")
    suspend fun clear(pkg: String, versionCode: Long): Int

    @Query("DELETE FROM scan_failed WHERE pkg = :pkg")
    suspend fun clearForPackage(pkg: String): Int

    @Query("SELECT (crashCount >= 2) FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode LIMIT 1")
    suspend fun isQuarantined(pkg: String, versionCode: Long): Boolean?
}
