package org.privacyguard.android.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.privacyguard.android.data.AppDatabase
import org.privacyguard.android.data.entity.AppScanResult
import org.privacyguard.android.data.entity.Capability
import org.privacyguard.android.data.entity.PolicyOp
import org.privacyguard.android.scan.tier1a.Tier1AScanner
import org.privacyguard.android.shield.ShieldProbe
import org.privacyguard.android.shield.ShieldTelemetry
import org.privacyguard.android.shield.TunnelStatus
import org.privacyguard.android.shizuku.ShellOps
import org.privacyguard.android.shizuku.ShizukuManager
import org.privacyguard.android.trigger.ReconciliationCoordinator
import rikka.shizuku.Shizuku

enum class ShizukuStatus {
    CONNECTED,
    PERMISSION_REQUIRED,
    UNAVAILABLE
}

enum class AppFilter {
    ALL,
    HIGH_RISK,
    TRACKERS_DETECTED,
    QUARANTINED
}

data class UiState(
    val shizukuStatus: ShizukuStatus = ShizukuStatus.UNAVAILABLE,
    val capabilities: List<Capability> = emptyList(),
    val shieldStatus: ShieldTelemetry = ShieldTelemetry(
        isPrivateDnsActive = false,
        privateDnsServerName = null,
        isVpnActive = false,
        isAlwaysOnLockdown = false,
        isSinkholeActive = false,
        egressIp = null,
        warpStatus = "off",
        tunnelStatus = TunnelStatus.DISCONNECTED
    ),
    val isScanning: Boolean = false,
    val searchQuery: String = "",
    val activeFilter: AppFilter = AppFilter.ALL,
    val allApps: List<AppScanResult> = emptyList(),
    val filteredApps: List<AppScanResult> = emptyList(),
    val selectedPackage: String? = null,
    val selectedApp: AppScanResult? = null,
    val selectedAppOps: List<PolicyOp> = emptyList(),
    val hasUnsafeRollback: Boolean = false,
    val isSelectedAppProtected: Boolean = false,
    val statusMessage: String? = null
)

class PrivacyGuardViewModel(
    application: Application,
    private val database: AppDatabase,
    private val shizukuManager: ShizukuManager
) : AndroidViewModel(application) {

    private val TAG = "PrivacyGuardVM"

    private val _shizukuStatus = MutableStateFlow(computeShizukuStatus())
    private val _shieldStatus = MutableStateFlow(UiState().shieldStatus)
    private val _isScanning = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _activeFilter = MutableStateFlow(AppFilter.ALL)
    private val _selectedPackage = MutableStateFlow<String?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)

    // Shizuku lifecycle listeners to prevent leaks
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _shizukuStatus.value = computeShizukuStatus()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _shizukuStatus.value = computeShizukuStatus()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        _shizukuStatus.value = computeShizukuStatus()
    }

    init {
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed registering Shizuku listeners", t)
        }
        reprobeShield()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed unregistering Shizuku listeners", t)
        }
    }

    private fun computeShizukuStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.UNAVAILABLE
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.CONNECTED
            } else {
                ShizukuStatus.PERMISSION_REQUIRED
            }
        } catch (_: Throwable) {
            ShizukuStatus.UNAVAILABLE
        }
    }

    // Reactive flow switching without collector leaks
    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedOpsFlow = _selectedPackage.flatMapLatest { pkg ->
        if (pkg == null) {
            flowOf(emptyList())
        } else {
            database.policyOpDao().observeByPackage(pkg)
        }
    }

    val uiState: StateFlow<UiState> = combine(
        _shizukuStatus,
        database.capabilityDao().observeAllCapabilities(),
        _shieldStatus,
        _isScanning,
        database.appScanResultDao().observeAllSortedByRisk(),
        _searchQuery,
        _activeFilter,
        _selectedPackage,
        selectedOpsFlow,
        _statusMessage
    ) { args: Array<Any?> ->
        val shizuku = args[0] as ShizukuStatus
        @Suppress("UNCHECKED_CAST")
        val capabilities = args[1] as List<Capability>
        val shield = args[2] as ShieldTelemetry
        val scanning = args[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val apps = args[4] as List<AppScanResult>
        val query = args[5] as String
        val filter = args[6] as AppFilter
        val selectedPkg = args[7] as String?
        @Suppress("UNCHECKED_CAST")
        val ops = args[8] as List<PolicyOp>
        val statusMsg = args[9] as String?

        // Derive filtered apps
        val filtered = apps.filter { app ->
            val matchesQuery = query.isBlank() || app.pkg.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.HIGH_RISK -> app.riskScore >= 0.70
                AppFilter.TRACKERS_DETECTED -> app.matchedTrackersJson != "[]" && app.matchedTrackersJson.isNotBlank()
                AppFilter.QUARANTINED -> app.scanStatus == 2 || app.detectedFramework == "QUARANTINED"
            }
            matchesQuery && matchesFilter
        }

        // Live derived selected app to ensure DB updates (OSINT verdicts, scores) reflect immediately
        val selectedApp = if (selectedPkg != null) apps.find { it.pkg == selectedPkg } else null
        val hasUnsafe = ops.any { it.status == PolicyOp.STATUS_ROLLED_BACK_UNSAFE }
        val isProtected = if (selectedPkg != null) ShellOps.isSafeListed(getApplication(), selectedPkg) else false

        UiState(
            shizukuStatus = shizuku,
            capabilities = capabilities,
            shieldStatus = shield,
            isScanning = scanning,
            searchQuery = query,
            activeFilter = filter,
            allApps = apps,
            filteredApps = filtered,
            selectedPackage = selectedPkg,
            selectedApp = selectedApp,
            selectedAppOps = ops,
            hasUnsafeRollback = hasUnsafe,
            isSelectedAppProtected = isProtected,
            statusMessage = statusMsg
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UiState()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: AppFilter) {
        _activeFilter.value = filter
    }

    fun selectPackage(pkg: String?) {
        _selectedPackage.value = pkg
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun requestShizukuPermission(requestCode: Int = 1001) {
        try {
            shizukuManager.requestPermission(requestCode)
        } catch (t: Throwable) {
            _statusMessage.value = "Shizuku permission request failed: ${t.message}"
        }
    }

    fun reprobeShield() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = ShieldProbe.checkShield(getApplication())
            _shieldStatus.value = status
        }
    }

    fun triggerScanAndReconcile() {
        if (_isScanning.value) return
        _isScanning.value = true
        _statusMessage.value = "Scanning apps and evaluating privacy risk..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ReconciliationCoordinator.triggerChangedPackagesCheck(getApplication())
                ReconciliationCoordinator.processPending(getApplication())
                _statusMessage.value = "Scan and policy reconciliation completed."
            } catch (t: Throwable) {
                Log.e(TAG, "Reconciliation failed", t)
                _statusMessage.value = "Scan failed: ${t.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    /**
     * Concrete component resolution & safe-listed system package protection.
     * Enqueues policy ops for all tracker services/receivers in pkg via upsertPreservingSnapshotAndGen.
     */
    fun disableTrackerComponents(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (ShellOps.isSafeListed(getApplication(), pkg)) {
                _statusMessage.value = "Cannot disable components on protected system package: $pkg"
                return@launch
            }

            val app = database.appScanResultDao().getByPackage(pkg) ?: return@launch
            val trackerSignatures = try {
                getApplication<Application>().assets.open("trackers_ac.bin").bufferedReader().useLines { lines ->
                    lines.filter { !it.startsWith("#") && !it.startsWith("TRACKER_AC_") && it.isNotBlank() }.toList()
                }
            } catch (_: Throwable) {
                emptyList<String>()
            }

            val t1 = try {
                Tier1AScanner.scanInstalledApp(getApplication(), pkg, trackerSignatures)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed resolving manifest components for $pkg", t)
                null
            } ?: return@launch

            val pm = getApplication<Application>().packageManager
            val pkgInfo = try {
                pm.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS).toLong()
                    )
                )
            } catch (_: Throwable) {
                null
            } ?: return@launch

            val targetComponents = mutableListOf<String>()

            fun checkAndAdd(name: String?) {
                if (name == null) return
                if (ShellOps.isSafeListed(getApplication(), pkg, name)) return
                for (sig in trackerSignatures) {
                    if (name == sig || name.startsWith("$sig.")) {
                        targetComponents.add(name)
                        break
                    }
                }
            }

            pkgInfo.services?.forEach { svc ->
                // Guard accessibility and notification listener services
                if (svc.permission != "android.permission.BIND_ACCESSIBILITY_SERVICE" &&
                    svc.permission != "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
                ) {
                    checkAndAdd(svc.name)
                }
            }
            pkgInfo.receivers?.forEach { checkAndAdd(it.name) }
            pkgInfo.providers?.forEach { checkAndAdd(it.name) }

            if (targetComponents.isEmpty()) {
                _statusMessage.value = "No modifiable tracker components found in $pkg"
                return@launch
            }

            val now = System.currentTimeMillis()
            val op = PolicyOp(
                pkg = pkg,
                versionCode = pkgInfo.longVersionCode,
                lastUpdateTime = pkgInfo.lastUpdateTime,
                op = ShellOps.OP_DISABLE_COMPONENTS,
                target = targetComponents.joinToString(","),
                desired = ShellOps.DESIRED_DISABLED,
                status = PolicyOp.STATUS_PENDING,
                nextAt = now
            )

            val opId = database.policyOpDao().upsertPreservingSnapshotAndGen(op, userInitiated = true)
            if (opId == -1L) {
                _statusMessage.value = "Operation locked: $pkg previously crashed when components were disabled (ROLLED_BACK_UNSAFE). Unlock before retrying."
                return@launch
            }
            _statusMessage.value = "Queued component disable for ${targetComponents.size} tracker components"
            shizukuManager.drainRunnableOps()
        }
    }

    /**
     * Enqueues Wi-Fi and background restrictions on pkg.
     */
    fun restrictBackgroundNetworking(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (ShellOps.isSafeListed(getApplication(), pkg)) {
                _statusMessage.value = "Cannot restrict networking on protected system package: $pkg"
                return@launch
            }

            val pm = getApplication<Application>().packageManager
            val pkgInfo = try {
                pm.getPackageInfo(pkg, 0)
            } catch (_: Throwable) {
                null
            } ?: return@launch

            val now = System.currentTimeMillis()
            val op = PolicyOp(
                pkg = pkg,
                versionCode = pkgInfo.longVersionCode,
                lastUpdateTime = pkgInfo.lastUpdateTime,
                op = ShellOps.OP_ISOLATE_WIFI,
                target = "background",
                desired = ShellOps.DESIRED_ISOLATED,
                status = PolicyOp.STATUS_PENDING,
                nextAt = now
            )

            val opId = database.policyOpDao().upsertPreservingSnapshotAndGen(op, userInitiated = true)
            if (opId == -1L) {
                _statusMessage.value = "Operation locked: $pkg previously crashed under network isolation (ROLLED_BACK_UNSAFE). Unlock before retrying."
                return@launch
            }
            _statusMessage.value = "Queued background network restrictions for $pkg"
            shizukuManager.drainRunnableOps()
        }
    }

    /**
     * Unlocks a previously crash-rolled-back op (ROLLED_BACK_UNSAFE) and immediately retries it.
     */
    fun unlockAndRetryPolicyOp(pkg: String, opType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val unlocked = database.policyOpDao().unlockRolledBackUnsafe(pkg, opType)
            if (unlocked > 0) {
                _statusMessage.value = "Unlocked previously crashing operation for $pkg. Retrying..."
                when (opType) {
                    ShellOps.OP_DISABLE_COMPONENTS -> disableTrackerComponents(pkg)
                    ShellOps.OP_ISOLATE_WIFI -> restrictBackgroundNetworking(pkg)
                }
            } else {
                _statusMessage.value = "No locked unsafe operation found to unlock for $pkg"
            }
        }
    }

    /**
     * Executes component state restoration serialized through ShizukuManager with read-back verification.
     */
    fun rollbackPackage(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _statusMessage.value = "Restoring default state for $pkg..."
            val success = shizukuManager.rollbackPackage(pkg)
            if (success) {
                _statusMessage.value = "Successfully restored components and policy state for $pkg"
            } else {
                _statusMessage.value = "Rollback finished with warnings; check component states"
            }
        }
    }

    companion object {
        fun provideFactory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext as Application
                val db = AppDatabase.getInstance(app)
                val shizuku = ShizukuManager.getInstance(app)
                return PrivacyGuardViewModel(app, db, shizuku) as T
            }
        }
    }
}
