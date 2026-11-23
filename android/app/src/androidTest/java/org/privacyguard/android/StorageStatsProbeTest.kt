package org.privacyguard.android

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageStatsProbeTest {

    private val allowedPackages = listOf(
        "com.ansangha.drdriving",
        "com.ludo.king",
        "com.rarlab.rar"
    )

    @Test
    fun testQueryStorageStats() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val targetPkg = args.getString("targetPkg")

        val packagesToQuery = if (!targetPkg.isNullOrEmpty()) {
            if (!allowedPackages.contains(targetPkg)) {
                fail("targetPkg '$targetPkg' is not in allowed probe packages: $allowedPackages")
            }
            listOf(targetPkg)
        } else {
            allowedPackages
        }

        val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            ?: run {
                fail("StorageStatsManager system service not available on context")
                return
            }

        for (pkg in packagesToQuery) {
            try {
                val stats = storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    pkg,
                    Process.myUserHandle()
                )

                val appBytes = stats.appBytes
                val dataBytes = stats.dataBytes
                val cacheBytes = stats.cacheBytes
                val externalCacheBytes = stats.externalCacheBytes

                val line = "M4C_STATS pkg=$pkg appBytes=$appBytes dataBytes=$dataBytes cacheBytes=$cacheBytes externalCacheBytes=$externalCacheBytes"

                Log.i("M4C_PROBE", line)
                println(line)

                val bundle = Bundle().apply {
                    putString("stats_line", line)
                    putString("pkg", pkg)
                    putLong("appBytes", appBytes)
                    putLong("dataBytes", dataBytes)
                    putLong("cacheBytes", cacheBytes)
                    putLong("externalCacheBytes", externalCacheBytes)
                }
                instrumentation.sendStatus(0, bundle)
            } catch (e: Throwable) {
                val err = "Failed querying StorageStats for $pkg: ${e.javaClass.name}: ${e.message}"
                Log.e("M4C_PROBE", err, e)
                System.err.println(err)
                throw e
            }
        }
    }
}
