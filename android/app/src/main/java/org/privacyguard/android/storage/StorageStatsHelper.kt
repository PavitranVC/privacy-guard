package org.privacyguard.android.storage

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager

data class PackageStorageStats(
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val externalCacheBytes: Long
)

object StorageStatsHelper {

    @Suppress("DEPRECATION")
    fun hasUsageStatsAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun queryPackageStats(context: Context, packageName: String): Result<PackageStorageStats> {
        return runCatching {
            val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
                ?: throw IllegalStateException("StorageStatsManager service not available on Context")
            val stats = storageStatsManager.queryStatsForPackage(
                StorageManager.UUID_DEFAULT,
                packageName,
                Process.myUserHandle()
            )
            PackageStorageStats(
                packageName = packageName,
                appBytes = stats.appBytes,
                dataBytes = stats.dataBytes,
                cacheBytes = stats.cacheBytes,
                externalCacheBytes = stats.externalCacheBytes
            )
        }
    }

    fun queryPackagesStats(context: Context, packageNames: List<String>): List<PackageStorageStats> {
        val result = mutableListOf<PackageStorageStats>()
        for (pkg in packageNames) {
            val queryResult = queryPackageStats(context, pkg)
            queryResult.onSuccess { stats ->
                result.add(stats)
            }.onFailure { exception ->
                if (exception is SecurityException) {
                    throw exception
                }
                if (exception is PackageManager.NameNotFoundException) {
                    // Skip uninstalled or missing package
                    return@onFailure
                }
                throw exception
            }
        }
        return result
    }

    fun getDataAvailableBytes(): Long {
        return StatFs(Environment.getDataDirectory().path).availableBytes
    }
}
