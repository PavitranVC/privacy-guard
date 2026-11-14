package org.privacyguard.android.scan.tier1a

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

data class Tier1AResult(
    val packageName: String,
    val versionCode: Long,
    val signingCertSha256: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val dangerousPermissions: List<String>,
    val hasBootCompleted: Boolean,
    val detectedTrackers: List<String>,
    val manifestRiskScore: Double
)

object Tier1AScanner {

    private val DANGEROUS_PERMISSIONS = setOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION"
    )

    private const val PERM_BOOT_COMPLETED = "android.permission.RECEIVE_BOOT_COMPLETED"

    /**
     * Executes an instant manifest scan (under 5 ms) over the target installed package.
     * Intentionally omits GET_ACTIVITIES to avoid bloated IPC Binder payloads.
     */
    fun scanInstalledApp(
        context: Context,
        packageName: String,
        trackerSignatures: List<String>
    ): Tier1AResult {
        val pm = context.packageManager
        val flags = PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SIGNING_CERTIFICATES

        val pkgInfo = pm.getPackageInfo(packageName, flags)
        val appInfo = pkgInfo.applicationInfo ?: throw IllegalStateException("Missing ApplicationInfo for $packageName")

        val baseApkPath = appInfo.sourceDir ?: ""
        val splitApkPaths = appInfo.splitSourceDirs?.toList() ?: emptyList()

        // 1. Compute Signing Certificate SHA-256
        val certSha256 = extractSigningCertSha256(pkgInfo)

        // 2. Identify Dangerous Permissions & Boot Completed
        val requestedPerms = pkgInfo.requestedPermissions ?: emptyArray()
        val foundDangerousPerms = mutableListOf<String>()
        var hasBoot = false

        for (perm in requestedPerms) {
            if (perm in DANGEROUS_PERMISSIONS) {
                foundDangerousPerms.add(perm)
            }
            if (perm == PERM_BOOT_COMPLETED) {
                hasBoot = true
            }
        }

        // 3. Match Component Class Names against Tracker Signatures
        // Component class names in manifest are declared names immune to R8 renaming
        val detectedTrackers = mutableSetOf<String>()

        fun checkComponent(name: String?) {
            if (name == null) return
            for (sig in trackerSignatures) {
                if (name.startsWith(sig) || name.contains(sig)) {
                    detectedTrackers.add(sig)
                }
            }
        }

        pkgInfo.services?.forEach { checkComponent(it.name) }
        pkgInfo.receivers?.forEach { checkComponent(it.name) }
        pkgInfo.providers?.forEach { checkComponent(it.name) }

        // 4. Calculate Instant Heuristic Manifest Risk Score
        var risk = 0.05
        risk += detectedTrackers.size * 0.15
        risk += foundDangerousPerms.size * 0.10
        if (hasBoot) risk += 0.05
        if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in foundDangerousPerms) risk += 0.35
        if ("android.permission.SYSTEM_ALERT_WINDOW" in foundDangerousPerms) risk += 0.20

        val finalScore = risk.coerceIn(0.0, 1.0)

        return Tier1AResult(
            packageName = packageName,
            versionCode = pkgInfo.longVersionCode,
            signingCertSha256 = certSha256,
            baseApkPath = baseApkPath,
            splitApkPaths = splitApkPaths,
            dangerousPermissions = foundDangerousPerms,
            hasBootCompleted = hasBoot,
            detectedTrackers = detectedTrackers.toList(),
            manifestRiskScore = finalScore
        )
    }

    private fun extractSigningCertSha256(pkgInfo: PackageInfo): String {
        val signingInfo = pkgInfo.signingInfo ?: return ""
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }

        if (signers.isNullOrEmpty()) return ""

        val certBytes = signers[0].toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(certBytes)

        return digest.joinToString("") { "%02x".format(it) }
    }
}
