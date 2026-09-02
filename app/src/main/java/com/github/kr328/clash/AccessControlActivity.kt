package com.github.kr328.clash

import android.Manifest.permission.INTERNET
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.AccessControlDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeTunPrefs
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AccessControlActivity : BaseActivity<AccessControlDesign>() {
    private var initial: Set<String>? = null
    private var initialMode: AccessControlMode? = null
    private var current: MutableSet<String>? = null

    override suspend fun main() {
        val service = ServiceStore(this)

        val bundle = restored

        val selected = bundle?.getStringArray("selected")?.toMutableSet()
            ?: withContext(Dispatchers.IO) { service.accessControlPackages.toMutableSet() }
        val initial = bundle?.getStringArray("initial")?.toSet() ?: selected.toSet()
        val initialMode = bundle?.getString("initialMode")
            ?.let { name -> AccessControlMode.entries.firstOrNull { it.name == name } }
            ?: withContext(Dispatchers.IO) { service.accessControlMode }

        this.initial = initial
        this.initialMode = initialMode
        this.current = selected

        defer {
            withContext(Dispatchers.IO) {
                val changed = initial != selected ||
                    initialMode != service.accessControlMode
                if (changed) {
                    service.accessControlPackages = selected.toSet()
                }
                if (changed && StatusClient(this@AccessControlActivity).isActive()) {
                    stopClashService()
                    withTimeoutOrNull(10_000) {
                        while (StatusClient(this@AccessControlActivity).isActive()) {
                            delay(200)
                        }
                    }
                    if (startClashService() != null) {
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
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName)
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectNone -> {
                            selected.clear()

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.SelectInvert -> {
                            val all = withContext(Dispatchers.Default) {
                                design.apps.map(AppInfo::packageName).toSet() - selected
                            }

                            selected.clear()
                            selected.addAll(all)

                            design.rebindAll()
                        }

                        AccessControlDesign.Request.Import -> {
                            val clipboard = getSystemService<ClipboardManager>()
                            val data = clipboard?.primaryClip

                            val text = data?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.text
                                ?.toString()

                            if (!text.isNullOrBlank()) {
                                val packages = text.split("\n")
                                    .map { line -> line.trim() }
                                    .filter { line -> line.isNotEmpty() }
                                    .toSet()
                                val all = design.apps.map(AppInfo::packageName).intersect(packages)

                                selected.clear()
                                selected.addAll(all)
                            }

                            design.rebindAll()
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

        initial?.let { outState.putStringArray("initial", it.toTypedArray()) }
        current?.let { outState.putStringArray("selected", it.toTypedArray()) }
        initialMode?.let { outState.putString("initialMode", it.name) }
    }

    private suspend fun loadApps(selected: Set<String>): List<AppInfo> {
        val chosen = selected.toSet()

        return withContext(Dispatchers.IO) {
            val reverse = uiStore.accessControlReverse
            val sort = uiStore.accessControlSort
            val systemApp = uiStore.accessControlSystemApp

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
        }
    }

    private val PackageInfo.isSystemApp: Boolean
        get() {
            return applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0
        }
}
