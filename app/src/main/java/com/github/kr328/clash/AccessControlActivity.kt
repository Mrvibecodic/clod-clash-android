package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.model.AppInfoSort
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.accessControlFingerprint
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeTunPrefs
import com.github.kr328.clash.util.SelectionOps
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AccessControlActivity : BaseActivity<AccessControlDesign>() {
    private var current: MutableSet<String>? = null

    override suspend fun main() {
        val service = ServiceStore(this)

        val selected = restored?.getStringArray("selected")?.toMutableSet()
            ?: withContext(Dispatchers.IO) { service.accessControlPackages.toMutableSet() }

        this.current = selected

        defer {
            val restart = withContext(Dispatchers.IO) {
                service.accessControlPackages = selected.toSet()

                accessControlFingerprint(service.accessControlMode, selected) != service.accessControlApplied &&
                    uiStore.enableVpn &&
                    StatusClient(this@AccessControlActivity).isActive()
            }
            if (restart) {
                val app = applicationContext

                Global.launch(Dispatchers.IO) {
                    app.stopClashService()
                    withTimeoutOrNull(10_000) {
                        while (StatusClient(app).isActive()) {
                            delay(200)
                        }
                    }
                    if (app.startClashService(unattended = true) != null) {
                        Log.w("Access control: VPN permission required, service not restarted")
                    }
                }
            }
        }

        val tunPrefs = withContext(Dispatchers.IO) { activeTunPrefs() }

        val design = AccessControlDesign(
            this,
            uiStore,
            service,
            selected,
            tunPrefs?.includePackages?.filter { runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess }?.toSet() ?: emptySet(),
            tunPrefs?.excludePackages?.toSet() ?: emptySet(),
        )

        setContentDesign(design)

        design.requests.send(AccessControlDesign.Request.ReloadApps)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        AccessControlDesign.Request.Back -> {
                            finish()
                        }

                        AccessControlDesign.Request.ReloadApps -> {
                            design.patchApps(loadApps(selected))
                        }

                        AccessControlDesign.Request.SelectAll -> {
                            applySelection(selected, SelectionOps.Op.SelectAll, design)
                        }

                        AccessControlDesign.Request.SelectNone -> {
                            applySelection(selected, SelectionOps.Op.SelectNone, design)
                        }

                        AccessControlDesign.Request.SelectInvert -> {
                            applySelection(selected, SelectionOps.Op.Invert, design)
                        }

                        AccessControlDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            val data = clipboard?.primaryClip

                            val text = data?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.text
                                ?.toString()
                                .orEmpty()

                            val imported = withContext(Dispatchers.IO) {
                                text.lineSequence()
                                    .map { line -> line.trim() }
                                    .filter { line -> line.isNotEmpty() }
                                    .filter { line -> runCatching { packageManager.getApplicationInfo(line, 0) }.isSuccess }
                                    .toSet()
                            }

                            if (imported.isEmpty()) {
                                design.showToast(R.string.clod_access_import_none, ToastDuration.Long)
                            } else {
                                selected.clear()
                                selected.addAll(imported)

                                design.rebindAll()
                                design.showToast(
                                    getString(R.string.clod_access_import_done, imported.size),
                                    ToastDuration.Short,
                                )
                            }
                        }

                        AccessControlDesign.Request.Export -> {
                            val clipboard = getSystemService<ClipboardManager>()

                            val data = ClipData.newPlainText(
                                "packages",
                                selected.joinToString("\n")
                            )

                            clipboard?.setPrimaryClip(data)
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        current?.let { outState.putStringArray("selected", it.toTypedArray()) }
    }

    private suspend fun applySelection(
        selected: MutableSet<String>,
        op: SelectionOps.Op,
        design: AccessControlDesign,
    ) {
        val visible = withContext(Dispatchers.Default) {
            design.apps.map(AppInfo::packageName).toSet()
        }
        val result = SelectionOps.apply(op, selected.toSet(), visible)

        selected.clear()
        selected.addAll(result)

        design.rebindAll()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) retainedApps = null
        super.onDestroy()
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> {
        val chosen = selected.toSet()
        val reverse = uiStore.accessControlReverse
        val sort = uiStore.accessControlSort
        val systemApp = uiStore.accessControlSystemApp

        retainedApps
            ?.takeIf { it.sort == sort && it.reverse == reverse && it.systemApp == systemApp }
            ?.let { return it.apps }

        return withContext(Dispatchers.IO) {
            val base = compareByDescending<AppInfo> { it.packageName in chosen }
            val comparator = if (reverse) base.thenDescending(sort) else base.then(sort)

            val pm = packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

            packages.asSequence()
                .filter {
                    it.packageName != packageName
                }
                .filter {
                    it.applicationInfo != null
                }
                .filter {
                    it.requestedPermissions?.contains(INTERNET) == true || it.applicationInfo!!.uid < android.os.Process.FIRST_APPLICATION_UID
                }
                .filter {
                    systemApp || !it.isSystemApp
                }
                .map {
                    it.toAppInfo(pm)
                }
                .sortedWith(comparator)
                .toList()
                .also { retainedApps = RetainedApps(sort, reverse, systemApp, it) }
        }
    }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }
}

private data class RetainedApps(
    val sort: AppInfoSort,
    val reverse: Boolean,
    val systemApp: Boolean,
    val apps: List<AppInfo>,
)

private var retainedApps: RetainedApps? = null
