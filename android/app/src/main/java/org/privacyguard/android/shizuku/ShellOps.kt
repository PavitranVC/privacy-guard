package org.privacyguard.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.DeadObjectException
import android.os.RemoteException
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.privacyguard.android.data.dao.CapabilityDao
import org.privacyguard.android.data.entity.Capability
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult

object ShellOps {

    const val OP_DISABLE_COMPONENTS = "disable_components"
    const val OP_ISOLATE_WIFI = "isolate_wifi"
    const val DESIRED_DISABLED = "DISABLED"
    const val DESIRED_ISOLATED = "ISOLATED"

    const val CAPABILITY_PM_COMPONENT_STATE = "pm_component_state_shell"
    const val CAPABILITY_CONNECTIVITY_CHAIN9 = "connectivity_chain9"
    const val ERR_SHELL_COMPONENT_STATE_DENIED = "SHELL_COMPONENT_STATE_DENIED"

    private const val TAG = "ShellOps"

    private val CORE_PROTECTED_PACKAGES = setOf(
        "org.privacyguard.android",
        "moe.shizuku.privileged.api",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.mms.service",
        "com.android.incallui",
        "com.google.android.dialer",
        "com.google.android.apps.messaging",
        "net.one97.paytm",
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping",
        "com.whatsapp",
        "com.csam.icici.bank.imobile",
        "com.sbi.lotusintouch",
        "com.axis.mobile",
        "com.snapwork.hdfc"
    )

    fun isCoreProtectedPackage(packageName: String): Boolean {
        return CORE_PROTECTED_PACKAGES.contains(packageName)
    }

    fun isDynamicUpiOrPaymentApp(context: Context, pkg: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")).setPackage(pkg)
            val flags = PackageManager.ResolveInfoFlags.of(
                (PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_DISABLED_COMPONENTS).toLong()
            )
            val activities = context.packageManager.queryIntentActivities(intent, flags)
            activities.isNotEmpty()
        }.getOrDefault(false)
    }

    /** Gate for future debloat module; intentionally not wired yet. */
    fun isProtectedForDebloat(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName || isCoreProtectedPackage(pkg) || isDynamicUpiOrPaymentApp(context, pkg)) {
            return true
        }

        return runCatching {
            val pm = context.packageManager
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())

            // Default launcher role
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
            if (pm.queryIntentActivities(homeIntent, flags).isNotEmpty()) return true

            // SMS role
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).setPackage(pkg)
            if (pm.queryIntentActivities(smsIntent, flags).isNotEmpty()) return true

            // Dialer role
            val dialIntent = Intent(Intent.ACTION_DIAL).setPackage(pkg)
            if (pm.queryIntentActivities(dialIntent, flags).isNotEmpty()) return true

            // WebView or permission controller roles
            if (pkg.contains("webview", ignoreCase = true) || pkg.contains("permissioncontroller", ignoreCase = true)) {
                return true
            }

            val appInfo = pm.getApplicationInfo(pkg, 0)
            val isNonUpdatedAndroid = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
                    (pkg.startsWith("com.android.") || pkg == "android")
            if (isNonUpdatedAndroid) return true

            false
        }.getOrDefault(false)
    }

    fun isSystemApp(context: Context, pkg: String): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private val PROTECTED_INITIALIZERS = setOf(
        "androidx.startup.InitializationProvider",
        "com.google.firebase.provider.FirebaseInitProvider"
    )

    private val PROTECTED_MESSAGING_SERVICES = setOf(
        "com.google.firebase.messaging.FirebaseMessagingService",
        "com.google.firebase.iid.FirebaseInstanceIdReceiver",
        "com.google.android.gms.measurement.AppMeasurementService",
        "com.google.android.gms.measurement.AppMeasurementReceiver"
    )

    private val SECURITY_DENIAL_REGEX = Regex("SecurityException|Permission Denial|not allowed", RegexOption.IGNORE_CASE)

    enum class IsolationResult(val statusString: String) {
        CHAIN3_SUCCESS("CHAIN3_PACKAGE_NETWORKING"),
        CHAIN9_SUCCESS("CHAIN9_BINDER_FIREWALL"),
        APPOPS_PARTIAL("APPOPS_STANDBY_RESTRICTED"),
        UNISOLATABLE_ON_WIFI("UNISOLATABLE_ON_WIFI")
    }

    data class PriorIsolationState(
        val bucket: String?,
        val appOpsMode: String?
    )

    data class NetworkIsolationOutcome(
        val result: IsolationResult,
        val observedString: String
    )

    val SETTABLE_BUCKETS = setOf("10", "20", "30", "40", "45", "active", "working_set", "frequent", "rare", "restricted")

    fun isSettableBucket(bucket: String?): Boolean {
        if (bucket.isNullOrBlank()) return false
        return SETTABLE_BUCKETS.contains(bucket.trim().lowercase())
    }

    fun parseStandbyBucketOutput(stdout: String?): String? {
        if (stdout.isNullOrBlank()) return null
        for (line in stdout.trim().lines()) {
            val l = line.trim()
            if (l.all { it in '0'..'9' } && l.isNotEmpty()) return l
            if (l.lowercase() in setOf("active", "working_set", "frequent", "rare", "restricted", "never")) return l
        }
        return null
    }

    fun parseAppOpsRunAnyOutput(stdout: String?): String {
        if (stdout.isNullOrBlank()) return "allow"
        val match = Regex("RUN_ANY_IN_BACKGROUND:\\s*([a-zA-Z]+)", RegexOption.IGNORE_CASE).find(stdout)
        if (match != null) {
            return match.groupValues[1].lowercase()
        }
        if (stdout.contains("No operations", ignoreCase = true)) {
            return "allow"
        }
        return "allow"
    }

    fun formatIsolationResult(
        baseResult: IsolationResult,
        priorState: PriorIsolationState?
    ): String {
        if (priorState == null || (priorState.bucket == null && priorState.appOpsMode == null)) {
            return baseResult.statusString
        }
        val b = priorState.bucket ?: ""
        val a = priorState.appOpsMode ?: ""
        return "${baseResult.statusString}|bucket=$b|appops=$a"
    }

    fun parsePriors(observed: String?): PriorIsolationState? {
        if (observed == null) return null
        if (!observed.startsWith("APPOPS_STANDBY_RESTRICTED")) return null
        val parts = observed.split("|")
        if (parts.size < 2) return null // Legacy format: no suffix
        var bucket: String? = null
        var appOps: String? = null
        for (i in 1 until parts.size) {
            val p = parts[i]
            when {
                p.startsWith("bucket=") -> {
                    val v = p.removePrefix("bucket=").trim()
                    if (v.isNotEmpty()) bucket = v
                }
                p.startsWith("appops=") -> {
                    val v = p.removePrefix("appops=").trim()
                    if (v.isNotEmpty()) appOps = v
                }
            }
        }
        if (bucket == null && appOps == null) return null
        return PriorIsolationState(bucket, appOps)
    }

    fun isComponentStateDeniedException(e: SecurityException): Boolean {
        return e.message?.contains("Shell cannot change component state", ignoreCase = true) == true
    }

    suspend fun checkComponentStateCapability(
        capabilityDao: CapabilityDao?,
        fingerprint: String
    ): Boolean {
        return capabilityDao?.isSupported(CAPABILITY_PM_COMPONENT_STATE, fingerprint) != false
    }

    suspend fun recordComponentStateDenial(
        capabilityDao: CapabilityDao?,
        fingerprint: String
    ) {
        capabilityDao?.upsert(
            Capability(CAPABILITY_PM_COMPONENT_STATE, false, fingerprint, System.currentTimeMillis())
        )
    }

    fun isUnknownCommand(stdout: String?, stderr: String?): Boolean {
        return (stdout?.contains("Unknown command", ignoreCase = true) == true) ||
                (stderr?.contains("Unknown command", ignoreCase = true) == true)
    }

    suspend fun checkChain9Capability(
        capabilityDao: CapabilityDao?,
        fingerprint: String
    ): Boolean {
        return capabilityDao?.isSupported(CAPABILITY_CONNECTIVITY_CHAIN9, fingerprint) != false
    }

    suspend fun recordChain9Unsupported(
        capabilityDao: CapabilityDao?,
        fingerprint: String
    ) {
        capabilityDao?.upsert(
            Capability(CAPABILITY_CONNECTIVITY_CHAIN9, false, fingerprint, System.currentTimeMillis())
        )
    }

    fun isSafeListed(context: Context, pkg: String, componentName: String? = null): Boolean {
        if (pkg == context.packageName) return true
        if (isCoreProtectedPackage(pkg) || isDynamicUpiOrPaymentApp(context, pkg) || isSystemApp(context, pkg)) return true

        if (componentName != null) {
            val simpleName = componentName.substringAfterLast('.')
            if (PROTECTED_INITIALIZERS.contains(componentName) || PROTECTED_INITIALIZERS.contains(simpleName)) return true
            if (PROTECTED_MESSAGING_SERVICES.contains(componentName) || PROTECTED_MESSAGING_SERVICES.contains(simpleName)) return true
            if (componentName.startsWith("androidx.work.impl.")) return true
            if (componentName.contains("GoogleApiActivity", ignoreCase = true)) return true
            if (componentName.contains("SignInHubActivity", ignoreCase = true)) return true
        }

        return false
    }

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

    fun probeCapabilities(shellService: IShellService): List<Capability> {
        val list = mutableListOf<Capability>()
        val fingerprint = Build.FINGERPRINT ?: "unknown"
        val now = System.currentTimeMillis()

        // 1. Connectivity probe
        val connResult = shellService.exec(arrayOf("cmd", "connectivity", "help"))
        val connOut = connResult.stdout + " " + connResult.stderr
        val chain3Supported = connOut.contains("set-chain3-enabled", ignoreCase = true) &&
                connOut.contains("set-package-networking-enabled", ignoreCase = true)
        list.add(Capability("connectivity_chain3", chain3Supported, fingerprint, now))

        // 2. AppOps probe with word boundary regex
        val appOpsResult = shellService.exec(arrayOf("cmd", "appops", "help"))
        val appOpsOut = appOpsResult.stdout + " " + appOpsResult.stderr
        val setWordRegex = Regex("\\bset\\b", RegexOption.IGNORE_CASE)
        val appOpsSupported = setWordRegex.containsMatchIn(appOpsOut)
        list.add(Capability("appops_set", appOpsSupported, fingerprint, now))

        // 3. PM disable-user probe
        val pmResult = shellService.exec(arrayOf("pm", "help"))
        val pmOut = pmResult.stdout + " " + pmResult.stderr
        val pmSupported = pmOut.contains("disable-user", ignoreCase = true)
        list.add(Capability("pm_disable_user", pmSupported, fingerprint, now))

        return list
    }

    suspend fun isolatePackageNetworking(
        context: Context,
        shellService: IShellService,
        pkg: String,
        capabilityDao: CapabilityDao? = null,
        existingObserved: String? = null
    ): NetworkIsolationOutcome {
        val pm = context.packageManager
        val pkgInfo = try {
            pm.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            Log.e(TAG, "Package not found for isolation: $pkg")
            return NetworkIsolationOutcome(IsolationResult.UNISOLATABLE_ON_WIFI, IsolationResult.UNISOLATABLE_ON_WIFI.statusString)
        }

        val appUid = pkgInfo.applicationInfo?.uid ?: 0
        val userId = appUid / 100000
        val fingerprint = Build.FINGERPRINT ?: "unknown"

        if (pkgInfo.sharedUserId != null) {
            Log.w(TAG, "Shared UID detected; refusing UID firewall isolation to prevent collateral blocking")
            return NetworkIsolationOutcome(IsolationResult.UNISOLATABLE_ON_WIFI, IsolationResult.UNISOLATABLE_ON_WIFI.statusString)
        }

        // Ladder Step 1: Chain 3 package networking
        val chain3Allowed = capabilityDao?.isSupported("connectivity_chain3", fingerprint) ?: true
        if (chain3Allowed) {
            try {
                val rEnable = shellService.exec(arrayOf("cmd", "connectivity", "set-chain3-enabled", "true"))
                checkSecurityDenial(rEnable.stderr)
                val r1 = shellService.exec(arrayOf("cmd", "connectivity", "set-package-networking-enabled", "false", pkg))
                checkSecurityDenial(r1.stderr)
                if (r1.exitCode == 0) {
                    return NetworkIsolationOutcome(IsolationResult.CHAIN3_SUCCESS, IsolationResult.CHAIN3_SUCCESS.statusString)
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: DeadObjectException) {
                throw e
            } catch (e: RemoteException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Ladder step 1 (chain3) failed", e)
            }
        }

        // Ladder Step 2: Firewall chain 9 fallback via shell
        if (checkChain9Capability(capabilityDao, fingerprint)) {
            try {
                val r2 = shellService.exec(arrayOf("cmd", "connectivity", "oem_deny_3", "add", appUid.toString()))
                checkSecurityDenial(r2.stderr)
                if (isUnknownCommand(r2.stdout, r2.stderr)) {
                    recordChain9Unsupported(capabilityDao, fingerprint)
                    Log.w(TAG, "Chain 9 unsupported on this device; recorded capability and skipping to step 3")
                } else if (r2.exitCode == 0) {
                    return NetworkIsolationOutcome(IsolationResult.CHAIN9_SUCCESS, IsolationResult.CHAIN9_SUCCESS.statusString)
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: DeadObjectException) {
                throw e
            } catch (e: RemoteException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Ladder step 2 (chain9) failed", e)
            }
        }

        // Ladder Step 3: AppOps ignore + Standby bucket restricted
        try {
            // Read prior state ONLY if not already preserved from a previous apply
            val existingPriors = parsePriors(existingObserved)
            val priors = if (existingPriors != null) {
                existingPriors
            } else {
                val rReadBucket = shellService.exec(arrayOf("am", "get-standby-bucket", "--user", userId.toString(), pkg))
                val priorBucket = parseStandbyBucketOutput(rReadBucket.stdout)
                val rReadAppOps = shellService.exec(arrayOf("cmd", "appops", "get", "--uid", appUid.toString(), "RUN_ANY_IN_BACKGROUND"))
                val priorAppOps = parseAppOpsRunAnyOutput(rReadAppOps.stdout)
                PriorIsolationState(priorBucket, priorAppOps)
            }

            val rAppOps = shellService.exec(arrayOf("cmd", "appops", "set", "--uid", appUid.toString(), "RUN_ANY_IN_BACKGROUND", "ignore"))
            checkSecurityDenial(rAppOps.stderr)
            val rBucket = shellService.exec(arrayOf("am", "set-standby-bucket", "--user", userId.toString(), pkg, "restricted"))
            checkSecurityDenial(rBucket.stderr)

            val verify = shellService.exec(arrayOf("cmd", "appops", "get", "--uid", appUid.toString(), "RUN_ANY_IN_BACKGROUND"))
            val verifyOut = verify.stdout
            val pattern = Regex("RUN_ANY_IN_BACKGROUND:\\s*ignore", RegexOption.IGNORE_CASE)
            if ((rAppOps.exitCode == 0 || rBucket.exitCode == 0) && pattern.containsMatchIn(verifyOut)) {
                val formattedStatus = formatIsolationResult(IsolationResult.APPOPS_PARTIAL, priors)
                return NetworkIsolationOutcome(IsolationResult.APPOPS_PARTIAL, formattedStatus)
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: DeadObjectException) {
            throw e
        } catch (e: RemoteException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Ladder step 3 (appops/standby) failed", e)
        }

        return NetworkIsolationOutcome(IsolationResult.UNISOLATABLE_ON_WIFI, IsolationResult.UNISOLATABLE_ON_WIFI.statusString)
    }

    /**
     * Resolves raw targets (handling comma-joined lists or single class names) to declared services
     * and broadcast receivers only, filtering out non-declared, provider/activity, accessibility,
     * notification listener, and safe-listed components.
     */
    fun effectiveTargets(
        context: Context,
        packageName: String,
        rawTargets: Collection<String>
    ): List<String> {
        val splitTargets = rawTargets
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (splitTargets.isEmpty()) return emptyList()

        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong()
        )
        val pkgInfo = try {
            pm.getPackageInfo(packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }

        // Collect valid declared services, excluding accessibility & notification listener services
        val validServices = pkgInfo.services?.filter { svc ->
            svc.permission != "android.permission.BIND_ACCESSIBILITY_SERVICE" &&
            svc.permission != "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        }?.map { it.name }?.toSet() ?: emptySet()

        // Collect valid declared receivers
        val validReceivers = pkgInfo.receivers?.map { it.name }?.toSet() ?: emptySet()

        val declaredValid = validServices + validReceivers
        val dynamicProtected = queryProtectedMessagingComponents(context, packageName)

        return splitTargets.mapNotNull { target ->
            val className = if (target.contains("/")) target.substringAfter("/") else target
            if (declaredValid.contains(className) &&
                !isSafeListed(context, packageName, className) &&
                !dynamicProtected.contains(className)
            ) {
                className
            } else {
                null
            }
        }.distinct()
    }

    fun createPreMutationSnapshot(
        context: Context,
        pkg: String,
        targetComponents: List<String>
    ): String {
        val pm = context.packageManager
        val effective = effectiveTargets(context, pkg, targetComponents)
        val snapshotMap = mutableMapOf<String, Int>()

        for (comp in effective) {
            val componentName = ComponentName(pkg, comp)
            snapshotMap[comp] = pm.getComponentEnabledSetting(componentName)
        }
        return Json.encodeToString(snapshotMap)
    }

    /**
     * Merges an existing snapshot with a newly captured snapshot.
     * Keys present in [existingJson] preserve their original pre-mutation state.
     * Only keys NEW in [currentJson] take their current value.
     * If [existingJson] is null, blank, or malformed, [currentJson] is returned.
     */
    fun mergeSnapshots(existingJson: String?, currentJson: String): String {
        if (existingJson.isNullOrBlank()) return currentJson
        return try {
            val existing = Json.decodeFromString<Map<String, Int>>(existingJson)
            val current = Json.decodeFromString<Map<String, Int>>(currentJson)
            val merged = current.toMutableMap()
            merged.putAll(existing)
            Json.encodeToString(merged)
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to parse/merge snapshots, returning currentJson: ${e.message}")
            } catch (_: Throwable) {
                // Ignore logger in unit test environments without android runtime
            }
            currentJson
        }
    }

    fun disableManifestComponents(
        context: Context,
        shellService: IShellService,
        pkg: String,
        targetComponents: List<String>
    ) {
        if (isSafeListed(context, pkg)) {
            throw IllegalArgumentException("Cannot disable components for protected package: $pkg")
        }

        val effective = effectiveTargets(context, pkg, targetComponents)
        if (effective.isEmpty()) {
            return
        }

        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        val userId = appInfo.uid / 100000

        val mutatedSoFar = mutableListOf<String>()

        try {
            for (comp in effective) {
                val componentName = ComponentName(pkg, comp)
                val res = shellService.exec(arrayOf("pm", "disable-user", "--user", userId.toString(), componentName.flattenToString()))
                checkSecurityDenial(res.stderr)
                if (res.exitCode != 0) {
                    rollbackComponents(shellService, pkg, userId, mutatedSoFar)
                    mutatedSoFar.clear()
                    throw IllegalStateException("pm disable-user failed for $comp: ${res.stderr}")
                }
                mutatedSoFar.add(comp)

                val observedState = pm.getComponentEnabledSetting(componentName)
                if (observedState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                    observedState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                ) {
                    rollbackComponents(shellService, pkg, userId, mutatedSoFar)
                    mutatedSoFar.clear()
                    throw IllegalStateException("Read-back verification mismatch on $comp: observed=$observedState")
                }
            }
        } catch (t: Throwable) {
            rollbackComponents(shellService, pkg, userId, mutatedSoFar)
            mutatedSoFar.clear()
            throw t
        }
    }

    private fun checkSecurityDenial(stderr: String) {
        if (SECURITY_DENIAL_REGEX.containsMatchIn(stderr)) {
            throw SecurityException(stderr.trim())
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
                val res = shellService.exec(arrayOf("pm", "default-state", "--user", userId.toString(), componentName.flattenToString()))
                if (res.exitCode != 0) {
                    try {
                        Log.w(TAG, "Failed to restore default-state for $comp: exit=${res.exitCode}, stderr=${res.stderr}")
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                try {
                    Log.w(TAG, "Exception during rollback of $comp: ${t.message}")
                } catch (_: Throwable) {}
            }
        }
    }

    fun killProcessSafely(shellService: IShellService, pkg: String, userId: Int): ShellResult {
        return shellService.exec(arrayOf("am", "kill", "--user", userId.toString(), pkg))
    }
}
