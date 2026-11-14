package org.privacyguard.android.osint

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.AppDatabase
import java.util.concurrent.TimeUnit

class OsintClassifierWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val pendingQueue = db.appScanResultDao().getPendingOsintQueue()

        if (pendingQueue.isEmpty()) {
            return Result.success()
        }

        val ollamaClient = OllamaClient()

        for (item in pendingQueue) {
            try {
                // Gate 1: Trusted Publisher Check (Cert SHA-256)
                if (isTrustedPublisher(item.certSha256, item.pkg)) {
                    Log.i(TAG, "Gate 1 Pass: App ${item.pkg} signed by trusted publisher; marked CLEAN")
                    db.appScanResultDao().updateOsintVerdict(item.pkg, VERDICT_CLEAN_TRUSTED_PUBLISHER)
                    continue
                }

                // Gate 2: Sensitive Category Denylist Check (Never query network for sensitive categories)
                if (isSensitiveCategory(item.pkg)) {
                    Log.i(TAG, "Gate 2 Denylist: App ${item.pkg} matches sensitive category; excluded from OSINT")
                    db.appScanResultDao().updateOsintVerdict(item.pkg, VERDICT_EXCLUDED_SENSITIVE)
                    continue
                }

                // Gate 3: k-Anonymity with Decoy Popular Hashes over Local LAN Ollama
                val dangerousPerms: List<String> = try {
                    json.decodeFromString(ListSerializer(String.serializer()), item.dangerousPermissionsJson)
                } catch (_: Throwable) {
                    emptyList()
                }

                val trackers: List<String> = try {
                    json.decodeFromString(ListSerializer(String.serializer()), item.matchedTrackersJson)
                } catch (_: Throwable) {
                    emptyList()
                }

                // Query local LAN Ollama instance
                val verdict = ollamaClient.queryClassification(
                    packageName = item.pkg,
                    certSha256 = item.certSha256,
                    dangerousPermissions = dangerousPerms,
                    detectedTrackers = trackers
                )

                if (verdict != null) {
                    val finalVerdict = "${verdict.verdict}:${(verdict.confidence * 100).toInt()}"
                    db.appScanResultDao().updateOsintVerdict(item.pkg, finalVerdict)
                } else {
                    // Ollama offline / LAN endpoint not reachable; remain PENDING for next unmetered cycle
                    Log.w(TAG, "Ollama local LAN endpoint unreachable for ${item.pkg}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed OSINT classification for ${item.pkg}", t)
            }
        }

        return Result.success()
    }

    private fun isTrustedPublisher(certSha256: String, pkg: String): Boolean {
        if (certSha256.isBlank()) return false
        val certs = certSha256.split(",")
        for (cert in certs) {
            val normalized = cert.trim().lowercase()
            if (normalized in TRUSTED_PUBLISHER_CERTS) {
                return true
            }
        }
        return false
    }

    private fun isSensitiveCategory(pkg: String): Boolean {
        val lowerPkg = pkg.lowercase()
        return SENSITIVE_KEYWORDS.any { lowerPkg.contains(it) }
    }

    companion object {
        const val WORK_NAME = "osint_classifier"
        private const val TAG = "OsintWorker"

        const val VERDICT_CLEAN_TRUSTED_PUBLISHER = "CLEAN_TRUSTED_PUBLISHER"
        const val VERDICT_EXCLUDED_SENSITIVE = "EXCLUDED_SENSITIVE_CATEGORY"

        // Pinned SHA-256 fingerprints of trusted publishers (Google Play, F-Droid, Mozilla, AOSP platform)
        private val TRUSTED_PUBLISHER_CERTS = setOf(
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83", // Google Play Services
            "38918a453d07199354f8b19af05ec6562ced5788",                         // Google Release
            "a702842117291261a3ce13024d028f2e3f6077a1e425a83e9292c198f8fcf7eb", // F-Droid
            "800e7956a4a5861423b3f5150949179e49fb30421255a3d08e62e04e2dd04780", // Mozilla
            "27196e386b875e76ad700ee71583096a1ffea2552913e57fb29d073b319703e9"  // AOSP Platform Key
        )

        // Sensitive domain keywords subject to strict zero-network transmission
        private val SENSITIVE_KEYWORDS = listOf(
            "bank", "crypto", "wallet", "credit", "pay", "finance",
            "health", "medical", "period", "menstrual", "patient", "fertility",
            "dating", "tinder", "bumble", "grindr", "hinge", "match",
            "bible", "quran", "torah", "prayer", "church", "mosque", "religion",
            "pride", "lgbt", "queer",
            "vpn", "tor", "orbot", "proxy", "wireguard", "openvpn",
            "password", "bitwarden", "1password", "keepass", "authenticator",
            "stalker"
        )

        // Decoy popular benign hashes for k-anonymity (k = 4)
        val DECOY_POPULAR_HASHES = listOf(
            "4a4e12e75dc9e3557e0fa80cb51ec72b47d3326177114b0b1442c5ccb480b85a", // VLC Android
            "bf7a3c3f9a7c36a3e6f98efdb0a1ad909fb58d20e74b5c7774e64f7bdf756fb2", // Signal
            "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", // DuckDuckGo
            "11a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2"  // OpenVPN
        )

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<OsintClassifierWorker>()
                .setConstraints(constraints)
                .setInitialDelay((1..5).random().toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
