package org.privacyguard.android.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.privacyguard.android.data.entity.AppScanResult

@Dao
interface AppScanResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(result: AppScanResult)

    @Query("SELECT * FROM app_scan_result WHERE pkg = :pkg LIMIT 1")
    suspend fun getByPackage(pkg: String): AppScanResult?

    @Query("SELECT * FROM app_scan_result WHERE maxConfidence < 0.90 AND osintVerdict = 'PENDING' ORDER BY riskScore DESC")
    suspend fun getPendingOsintQueue(): List<AppScanResult>

    @Query("UPDATE app_scan_result SET osintVerdict = :verdict WHERE pkg = :pkg")
    suspend fun updateOsintVerdict(pkg: String, verdict: String): Int

    @Query("SELECT * FROM app_scan_result ORDER BY riskScore DESC")
    suspend fun getAllSortedByRisk(): List<AppScanResult>

    @Query("DELETE FROM app_scan_result WHERE pkg = :pkg")
    suspend fun delete(pkg: String): Int
}
