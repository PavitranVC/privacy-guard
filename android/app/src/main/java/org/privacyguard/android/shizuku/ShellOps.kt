package org.privacyguard.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadObjectException
import android.os.RemoteException
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.entity.Capability
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult

object ShellOps {

    private const val TAG = "ShellOps"

    // Safe-list constants strictly protecting core platform and Firebase infrastructure
    private val PROTECTED_PACKAGES = setOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.gsf",
        "moe.shizuku.privileged.api"
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
        APPOPS_PARTIAL("APPLIED_APPOPS_PARTIAL"),
        UNISOLATABLE_ON_WIFI("UNISOLATABLE_ON_WIFI")
    }

    /**
     * Checks if a package or component is safe-listed and protected from mutation.
     */
    fun isSafeListed(
        context: Context,
        pkg: String,
        componentClassName: String? = null
    ): Boolean {
        if (pkg == context.packageName) return true
        if (PROTECTED_PACKAGES.contains(pkg)) return true

        val pm = context.packageManager
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                // Strictly protect system apps from component modification
                return true
            }
        } catch (e: Exception) {
            return true
        }

        if (componentClassName != null) {
            if (componentClassName.startsWith("androidx.work.impl.")) return true

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
     * Dynamically discovers all Firebase and Google Cloud Messaging intent handlers
     * on the target package to add them to the protected safe-list.
     */
    fun queryProtectedMessagingComponents(context: Context, pkg: String): Set<String> {
        val pm = context.packageManager
        val protectedSet = mutableSetOf<String>()

        val actions = listOf(
            "com.google.firebase.MESSAGING_EVENT",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.firebase.INSTANCE_ID_EVENT"
        )

        for (action in actions) {
            val intent = Intent(action).setPackage(pkg)
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())
            val services = pm.queryIntentServices(intent, flags)
            for (resolveInfo in services) {
                resolveInfo.serviceInfo?.name?.let { protectedSet.add(it) }
            }
            val receivers = pm.queryBroadcastReceivers(intent, flags)
            for (resolveInfo in receivers) {
                resolveInfo.activityInfo?.name?.let { protectedSet.add(it) }
            }
        }

        return protectedSet
    }

    /**
     * Probes shell capabilities (connectivity, appops, pm) and caches in capability entity list.
     */
    fun probeCapabilities(shellService: IShellService): List<Capability> {
        val list = mutableListOf<Capability>()
        val fingerprint = Build.FINGERPRINT ?: "unknown"
        val now = System.currentTimeMillis()

        // 1. Connectivity probe: requires BOTH chain3 enable and package networking verbs
        val connResult = shellService.exec(arrayOf("cmd", "connectivity", "help"))
        val connOut = connResult.stdout + " " + connResult.stderr
        val chain3Supported = connOut.contains("set-chain3-enabled", ignoreCase = true) &&
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
     * 2. Firewall chain 9 (oem_deny_3) binder fallback
     * 3. cmd appops set --uid <uid> RUN_ANY_IN_BACKGROUND ignore + am set-standby-bucket --user <userId> <pkg> restricted
     * 4. honest fallback status "UNISOLATABLE_ON_WIFI"
     */
    fun isolatePackageNetworking(
        context: Context,
        shellService: IShellService,
        pkg: String
    ): IsolationResult {
        val pm = context.packageManager
        val pkgInfo = try {
            pm.getPackageInfo(pkg, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Package not found for isolation")
            return IsolationResult.UNISOLATABLE_ON_WIFI
        }

        val appUid = pkgInfo.applicationInfo?.uid ?: 0
        val userId = appUid / 100000

        // Invariant: If target app shares UID with other apps, UID firewall isolation cannot safely isolate it alone
        if (pkgInfo.sharedUserId != null) {
            Log.w(TAG, "Shared UID detected; refusing UID firewall isolation to prevent collateral blocking")
            return IsolationResult.UNISOLATABLE_ON_WIFI
        }

        // Ladder Step 1: Chain 3 package networking (oem_deny_3 verbs)
        try {
            shellService.exec(arrayOf("cmd", "connectivity", "set-chain3-enabled", "true"))
            val r1 = shellService.exec(arrayOf("cmd", "connectivity", "set-package-networking-enabled", "false", pkg))
            if (r1.exitCode == 0) {
                return IsolationResult.CHAIN3_SUCCESS
            }
        } catch (e: DeadObjectException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 1 (chain3) failed", e)
        }

        // Ladder Step 2: OEM chain 9 binder/cmd firewall rule
        try {
            val r2 = shellService.exec(arrayOf("cmd", "connectivity", "oem_deny_3", "add", appUid.toString()))
            if (r2.exitCode == 0) {
                return IsolationResult.CHAIN9_SUCCESS
            }
        } catch (e: DeadObjectException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 2 (chain9) failed", e)
        }

        // Ladder Step 3: AppOps ignore + Standby bucket restricted
        try {
            val rAppOps = shellService.exec(arrayOf("cmd", "appops", "set", "--uid", appUid.toString(), "RUN_ANY_IN_BACKGROUND", "ignore"))
            val rBucket = shellService.exec(arrayOf("am", "set-standby-bucket", "--user", userId.toString(), pkg, "restricted"))

            // Verify AppOps read-back: must match regex RUN_ANY_IN_BACKGROUND:\s*ignore
            val verify = shellService.exec(arrayOf("cmd", "appops", "get", "--uid", appUid.toString(), "RUN_ANY_IN_BACKGROUND"))
            val verifyOut = verify.stdout
            val pattern = Regex("RUN_ANY_IN_BACKGROUND:\\s*ignore", RegexOption.IGNORE_CASE)
            if ((rAppOps.exitCode == 0 || rBucket.exitCode == 0) && pattern.containsMatchIn(verifyOut)) {
                return IsolationResult.APPOPS_PARTIAL
            }
        } catch (e: DeadObjectException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 3 (appops/standby) failed", e)
        }

        // Ladder Step 4: Honest fallback status
        return IsolationResult.UNISOLATABLE_ON_WIFI
    }

    /**
     * Reads pre-mutation component enabled states into a serialized JSON snapshot.
     */
    fun createPreMutationSnapshot(
        context: Context,
        pkg: String,
        targetComponents: List<String>
    ): String {
        val pm = context.packageManager
        val snapshotMap = mutableMapOf<String, Int>()
        for (comp in targetComponents) {
            val componentName = ComponentName(pkg, comp)
            snapshotMap[comp] = pm.getComponentEnabledSetting(componentName)
        }
        return Json.encodeToString(snapshotMap)
    }

    /**
     * Mutates manifest Service/Receiver components via shell pm disable-user --user $userId.
     * Throws on read-back verification mismatch to trigger immediate rollback.
     */
    fun disableManifestComponents(
        context: Context,
        shellService: IShellService,
        pkg: String,
        targetComponents: List<String>
    ) {
        if (isSafeListed(context, pkg)) {
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

        val dynamicProtected = queryProtectedMessagingComponents(context, pkg)
        val userId = (pkgInfo.applicationInfo?.uid ?: 0) / 100000

        val mutatedSoFar = mutableListOf<String>()

        for (comp in targetComponents) {
            if (!declaredValid.contains(comp)) {
                continue
            }
            if (isSafeListed(context, pkg, comp) || dynamicProtected.contains(comp)) {
                continue
            }

            val componentName = ComponentName(pkg, comp)
            val res = shellService.exec(arrayOf("pm", "disable-user", "--user", userId.toString(), componentName.flattenToString()))
            if (res.exitCode != 0) {
                // Rollback any mutated components
                rollbackComponents(shellService, pkg, userId, mutatedSoFar)
                throw IllegalStateException("pm disable-user failed for $comp: ${res.stderr}")
            }
            mutatedSoFar.add(comp)

            // Read-back verification
            val observedState = pm.getComponentEnabledSetting(componentName)
            if (observedState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                observedState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            ) {
                rollbackComponents(shellService, pkg, userId, mutatedSoFar)
                throw IllegalStateException("Read-back verification mismatch on $comp: observed=$observedState")
            }
        }
    }

    private fun rollbackComponents(
        shellService: IShellService,
        pkg: String,
        userId: Int,
        components: List<String>
    ) {
        for (comp in components) {
            try {
                val componentName = ComponentName(pkg, comp)
                shellService.exec(arrayOf("pm", "default-state", "--user", userId.toString(), componentName.flattenToString()))
            } catch (ignored: Throwable) {}
        }
    }

    /**
     * Terminate target process safely via am kill --user $userId.
     */
    fun killProcessSafely(shellService: IShellService, pkg: String, userId: Int): ShellResult {
        return shellService.exec(arrayOf("am", "kill", "--user", userId.toString(), pkg))
    }
}
