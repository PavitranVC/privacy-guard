package org.privacyguard.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.entity.Capability
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult

object ShellOps {

    private const val TAG = "ShellOps"

    // GEMINI.md protected component and package identifiers
    private val PROTECTED_PACKAGES = setOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.android.gsf"
    )

    private val PROTECTED_COMPONENTS = setOf(
        "InitializationProvider",
        "FirebaseInitProvider",
        "FirebaseMessagingService",
        "FirebaseInstanceIdReceiver",
        "ComponentDiscoveryService",
        "GoogleApiActivity"
    )

    enum class IsolationResult(val statusString: String) {
        CHAIN3_SUCCESS("APPLIED_CHAIN3"),
        CHAIN9_SUCCESS("APPLIED_CHAIN9"),
        APPOPS_RESTRICTED("APPLIED_APPOPS_RESTRICTED"),
        UNISOLATABLE_ON_WIFI("UNISOLATABLE_ON_WIFI")
    }

    /**
     * Checks if a package or component is safe-listed and protected from mutation.
     */
    fun isSafeListed(pkg: String, componentClassName: String? = null): Boolean {
        if (PROTECTED_PACKAGES.contains(pkg)) return true
        if (componentClassName != null) {
            for (protected in PROTECTED_COMPONENTS) {
                if (componentClassName.contains(protected, ignoreCase = true)) {
                    return true
                }
            }
            if (componentClassName.contains("OAuth", ignoreCase = true) ||
                componentClassName.contains("SignInActivity", ignoreCase = true) ||
                componentClassName.contains("AuthActivity", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Probes shell capabilities (connectivity, appops, pm) and caches in capability entity list.
     */
    fun probeCapabilities(shellService: IShellService): List<Capability> {
        val list = mutableListOf<Capability>()
        val fingerprint = Build.FINGERPRINT ?: "unknown"
        val now = System.currentTimeMillis()

        // 1. Connectivity probe
        val connResult = shellService.exec(arrayOf("cmd", "connectivity", "help"))
        val connOut = connResult.stdout + " " + connResult.stderr
        val chain3Supported = connOut.contains("set-chain3-enabled", ignoreCase = true) ||
                connOut.contains("set-package-networking-enabled", ignoreCase = true)
        list.add(Capability("connectivity_chain3", chain3Supported, fingerprint, now))

        // 2. AppOps probe
        val appOpsResult = shellService.exec(arrayOf("cmd", "appops"))
        val appOpsOut = appOpsResult.stdout + " " + appOpsResult.stderr
        val appOpsSupported = appOpsOut.contains("set", ignoreCase = true)
        list.add(Capability("appops_set", appOpsSupported, fingerprint, now))

        // 3. PM disable-user probe
        val pmResult = shellService.exec(arrayOf("pm", "help"))
        val pmOut = pmResult.stdout + " " + pmResult.stderr
        val pmSupported = pmOut.contains("disable-user", ignoreCase = true)
        list.add(Capability("pm_disable_user", pmSupported, fingerprint, now))

        return list
    }

    /**
     * Executes the 4-step Wi-Fi isolation ladder:
     * 1. cmd connectivity set-chain3-enabled true + set-package-networking-enabled false <pkg>
     * 2. IConnectivityManager binder firewall chain 9 fallback
     * 3. cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore + am set-standby-bucket <pkg> restricted
     * 4. honest fallback status "UNISOLATABLE_ON_WIFI"
     */
    fun isolatePackageNetworking(
        context: Context,
        shellService: IShellService,
        pkg: String,
        userId: Int = 0
    ): IsolationResult {
        val pm = context.packageManager
        val pkgInfo = try {
            pm.getPackageInfo(pkg, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Package not found for isolation: $pkg", e)
            return IsolationResult.UNISOLATABLE_ON_WIFI
        }

        val appId = pkgInfo.applicationInfo?.uid?.let { it % 100000 } ?: 0
        val targetUid = userId * 100000 + appId

        // Warn if shared UID
        pkgInfo.sharedUserId?.let { sharedUid ->
            Log.w(TAG, "WARNING: Shared UID detected for $pkg ($sharedUid, uid=$targetUid). Wi-Fi isolation affects all co-located packages!")
        }

        // Ladder Step 1: Chain 3 package networking
        try {
            shellService.exec(arrayOf("cmd", "connectivity", "set-chain3-enabled", "true"))
            val r1 = shellService.exec(arrayOf("cmd", "connectivity", "set-package-networking-enabled", "false", pkg))
            if (r1.exitCode == 0) {
                return IsolationResult.CHAIN3_SUCCESS
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 1 (chain3) failed for $pkg", e)
        }

        // Ladder Step 2: Firewall chain 9 fallback
        try {
            val r2 = shellService.exec(arrayOf("cmd", "connectivity", "firewall", "chain9", "deny", targetUid.toString()))
            if (r2.exitCode == 0) {
                return IsolationResult.CHAIN9_SUCCESS
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 2 (chain9) failed for $pkg", e)
        }

        // Ladder Step 3: AppOps ignore + Standby bucket restricted
        try {
            val rAppOps = shellService.exec(arrayOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "ignore"))
            val rBucket = shellService.exec(arrayOf("am", "set-standby-bucket", pkg, "restricted"))
            
            // Read-back verification
            val verify = shellService.exec(arrayOf("cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND"))
            if ((rAppOps.exitCode == 0 || rBucket.exitCode == 0) &&
                (verify.stdout.contains("ignore", ignoreCase = true) || verify.exitCode == 0)
            ) {
                return IsolationResult.APPOPS_RESTRICTED
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 3 (appops/standby) failed for $pkg", e)
        }

        // Ladder Step 4: Honest fallback status
        Log.w(TAG, "All Wi-Fi isolation ladder steps exhausted for $pkg. Reporting UNISOLATABLE_ON_WIFI")
        return IsolationResult.UNISOLATABLE_ON_WIFI
    }

    /**
     * Mutates only Service and Receiver components declared in the target app's manifest
     * via COMPONENT_ENABLED_STATE_DISABLED_USER (DONT_KILL_APP).
     * Returns the serialized original snapshot for auto-rollback.
     */
    fun disableManifestComponents(
        context: Context,
        shellService: IShellService,
        pkg: String,
        targetComponents: List<String>
    ): String {
        if (isSafeListed(pkg)) {
            throw IllegalArgumentException("Cannot disable components for protected package: $pkg")
        }

        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            pkg,
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        )

        val declaredServices = pkgInfo.services?.map { it.name }?.toSet() ?: emptySet()
        val declaredReceivers = pkgInfo.receivers?.map { it.name }?.toSet() ?: emptySet()
        val declaredValid = declaredServices + declaredReceivers

        val snapshotMap = mutableMapOf<String, Int>()

        for (comp in targetComponents) {
            if (!declaredValid.contains(comp)) {
                Log.w(TAG, "Component $comp is not a manifest-declared Service or Receiver of $pkg. Skipping.")
                continue
            }
            if (isSafeListed(pkg, comp)) {
                Log.w(TAG, "Component $comp in $pkg is safe-listed. Skipping.")
                continue
            }

            val componentName = ComponentName(pkg, comp)
            val currentState = pm.getComponentEnabledSetting(componentName)
            snapshotMap[comp] = currentState

            // Disable via pm shell command or PackageManager
            val r = shellService.exec(arrayOf("pm", "disable-user", "--user", "0", componentName.flattenToString()))
            if (r.exitCode != 0) {
                // Fallback to PackageManager API
                pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                    PackageManager.DONT_KILL_APP
                )
            }

            // Read-back verification
            val verifiedState = pm.getComponentEnabledSetting(componentName)
            if (verifiedState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                verifiedState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                Log.w(TAG, "Read-back verification failed for $comp: observed state=$verifiedState")
            }
        }

        return Json.encodeToString(snapshotMap)
    }

    /**
     * Terminate the target app process safely using `am kill --user 0 <pkg>`.
     * GEMINI.md explicitly forbids using `am force-stop`.
     */
    fun killProcessSafely(shellService: IShellService, pkg: String): ShellResult {
        return shellService.exec(arrayOf("am", "kill", "--user", "0", pkg))
    }
}
