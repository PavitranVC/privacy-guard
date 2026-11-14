package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.privacyguard.android.data.entity.ScanFailed

@Dao
interface ScanFailedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordCrash(failed: ScanFailed)

    @Query("SELECT crashCount FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode LIMIT 1")
    suspend fun getCrashCount(pkg: String, versionCode: Long): Int?

    @Query("SELECT * FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode LIMIT 1")
    suspend fun getScanFailed(pkg: String, versionCode: Long): ScanFailed?

    @Query("DELETE FROM scan_failed WHERE pkg = :pkg")
    suspend fun clearForPackage(pkg: String): Int

    @Query("SELECT (crashCount >= 2) FROM scan_failed WHERE pkg = :pkg AND versionCode = :versionCode LIMIT 1")
    suspend fun isQuarantined(pkg: String, versionCode: Long): Boolean?
}
