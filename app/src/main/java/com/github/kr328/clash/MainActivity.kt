package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import android.net.Uri
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.model.PanelGroup
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.compose.screen.ProviderFileState
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.GeoData
import com.github.kr328.clash.util.patchSubscriptionGroup
import com.github.kr328.clash.util.ProfileUpdates
import com.github.kr328.clash.service.subscription.reportSubscriptionAlerts
import com.github.kr328.clash.service.util.profileLogoFile
import com.github.kr328.clash.service.util.SessionClock
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.querySubscriptionGroups
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.compose.screen.UpdateState
import com.github.kr328.clash.update.ApkInstaller
import com.github.kr328.clash.update.UpdatePrompt
import com.github.kr328.clash.update.Updater
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    override fun onProfileUpdateCompleted(uuid: UUID?) {
        super.onProfileUpdateCompleted(uuid)

        uuid?.let { ProfileUpdates.finish(it) }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        super.onProfileUpdateFailed(uuid, reason)

        uuid?.let { ProfileUpdates.finish(it) }
    }

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        if (!design.hasProfiles) {
            design.selectTab(MainTab.Subscriptions)
        }

        ProfileUpdates.prune()

        launch {
            ProfileUpdates.running.collect { design.setUpdatingProfiles(it.keys) }
        }

        design.loadVersionName()

        if (UpdatePrompt.shouldCheckInBackground(this)) {
            launch { design.checkUpdate(manual = false) }
        }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            design.fetch()

                            if (awaitingInstallPermission &&
                                ApkInstaller.canInstall(this@MainActivity)
                            ) {
                                awaitingInstallPermission = false

                                launch { design.startUpdate() }
                            }
                        }
                        Event.ClashStop -> {
                            offlineDelays = emptyMap()

                            design.fetch()

                            launch {
                                try {
                                    withProfile { queryActive() }?.let {
                                        reportSubscriptionAlerts(it.uuid)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w("Subscription alerts: $e", e)
                                }
                            }
                        }
                        Event.ServiceRecreated,
                        Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.ReloadProxies -> design.reloadProxyGroups()
                        is MainDesign.Request.ReloadGroup ->
                            design.reloadProxyGroup(request.index)
                        is MainDesign.Request.SelectProxy -> {
                            proxyGroupNames.getOrNull(request.index)?.let { group ->
                                if (serversReadOnly) {
                                    return@let
                                }

                                if (offlineGroups.isNotEmpty()) {
                                    withClash { rememberSelection(group, request.name) }

                                    offlineSelections[group] = request.name

                                    design.fillOfflineProxyGroup(request.index)

                                    return@let
                                }

                                val patched = withClash { patchSelector(group, request.name) }

                                if (patched) {
                                    design.reloadProxyGroup(request.index)
                                } else {
                                    design.showToast(
                                        DesignR.string.clod_select_failed,
                                        ToastDuration.Long,
                                    )
                                }
                            }
                        }
                        is MainDesign.Request.UrlTest -> launch { design.runHealthCheck() }
                        is MainDesign.Request.ToggleFavorite -> {
                            favoritesProfile?.let { profile ->
                                val current = uiStore.favorites(profile)
                                val next = if (request.name in current) {
                                    current - request.name
                                } else {
                                    current + request.name
                                }

                                uiStore.setFavorites(profile, next)

                                design.setFavorites(next)
                            }
                        }
                        is MainDesign.Request.PatchMode -> {
                            val locked = withProfile { queryActive() }
                                ?.let { queryPanelInfo(it.uuid)?.lockMode } == true

                            if (locked) {
                                design.showToast(
                                    DesignR.string.clod_mode_locked_toast,
                                    ToastDuration.Long,
                                )
                            } else {
                                withClash {
                                    val override = queryOverride(Clash.OverrideSlot.Session)

                                    override.mode = request.mode

                                    patchOverride(Clash.OverrideSlot.Session, override)
                                }

                                design.fetch()
                            }
                        }
                        is MainDesign.Request.OpenUrl -> openExternalUrl(request.url)
                        MainDesign.Request.CheckUpdate ->
                            launch { design.checkUpdate(manual = true) }

                        MainDesign.Request.UpdateNow -> launch { design.startUpdate() }
                        MainDesign.Request.UpdateSkip -> {
                            pendingUpdate?.let { UpdatePrompt.skip(this@MainActivity, it.manifest.versionCode) }

                            pendingUpdate = null

                            design.setUpdate(null)
                        }
                        MainDesign.Request.NewProfile ->
                            startActivity(AddProfileActivity::class.intent)
                        MainDesign.Request.UpdateAllProfiles -> {
                            launch {
                                var targets = emptyList<UUID>()

                                try {
                                    targets = withProfile {
                                        queryAll()
                                            .filter { it.imported && it.type != Profile.Type.File }
                                            .map { it.uuid }
                                    }

                                    if (targets.isEmpty()) {
                                        design.showToast(
                                            DesignR.string.clod_sub_nothing_to_update,
                                            ToastDuration.Short,
                                        )

                                        return@launch
                                    }

                                    ProfileUpdates.start(targets)

                                    withProfile { targets.forEach { update(it) } }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    targets.forEach { ProfileUpdates.finish(it) }

                                    design.showExceptionToast(e)
                                }
                            }
                        }
                        is MainDesign.Request.ActivateProfile -> {
                            val profile = request.profile

                            if (profile.imported) {
                                withProfile { setActive(profile) }
                            } else {
                                design.showToast(
                                    resId = DesignR.string.active_unsaved_tips,
                                    duration = ToastDuration.Long,
                                    actionLabel = DesignR.string.edit,
                                    onAction = {
                                        startActivity(
                                            PropertiesActivity::class.intent
                                                .setUUID(profile.uuid),
                                        )
                                    },
                                )
                            }
                        }
                        is MainDesign.Request.UpdateProfile -> {
                            launch {
                                val uuid = request.profile.uuid

                                ProfileUpdates.start(listOf(uuid))

                                try {
                                    withProfile { update(uuid) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    ProfileUpdates.finish(uuid)

                                    design.showExceptionToast(e)
                                }
                            }
                        }
                        is MainDesign.Request.EditProfile ->
                            startActivity(
                                PropertiesActivity::class.intent.setUUID(request.profile.uuid),
                            )
                        is MainDesign.Request.DeleteProfile ->
                            withProfile { delete(request.profile.uuid) }
                        MainDesign.Request.AllowNotifications -> {
                            design.setNotificationPrompt(false)

                            launch {
                                requestNotifications()

                                uiStore.notificationsAsked = true

                                if (!clashRunning) {
                                    design.startClash()
                                }
                            }
                        }
                        MainDesign.Request.SkipNotifications -> {
                            uiStore.notificationsAsked = true

                            design.setNotificationPrompt(false)

                            if (!clashRunning) {
                                design.startClash()
                            }
                        }
                        MainDesign.Request.DismissNotifications ->
                            design.setNotificationPrompt(false)
                        is MainDesign.Request.SetSubscriptionGroup -> {
                            patchSubscriptionGroup(request.profile.uuid, request.group)

                            design.fetch()
                        }
                        MainDesign.Request.OpenAccessControl ->
                            startActivity(AccessControlActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenAppSettings ->
                            startActivity(AppSettingsActivity::class.intent)
                        MainDesign.Request.OpenNetworkSettings ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        MainDesign.Request.OpenMetaSettings ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.LoadAbout -> design.loadAbout()
                        is MainDesign.Request.SetAutoCheckUpdate ->
                            withContext(Dispatchers.IO) {
                                AppStore(this@MainActivity).autoCheckUpdate = request.enabled
                            }

                        is MainDesign.Request.SetPrerelease ->
                            withContext(Dispatchers.IO) {
                                AppStore(this@MainActivity).nightlyChannel = request.enabled
                            }

                        MainDesign.Request.LoadRoutingData ->
                            design.loadRoutingData()

                        MainDesign.Request.UpdateRoutingData ->
                            launch { design.updateRoutingData() }
                    }
                }
                if (clashRunning && activityStarted) {
                    ticker.onReceive {
                        design.fetchTraffic()
                        design.fetchSession()

                        ProfileUpdates.prune()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val session = if (clashRunning) {
            withContext(Dispatchers.IO) {
                ServiceStore(this@MainActivity).run { clashStartedAt to clashStartedElapsed }
            }
        } else {
            0L to 0L
        }

        sessionStartedAt = session.first
        sessionStartedElapsed = session.second

        fetchSession()

        val state = withClash {
            queryTunnelState()
        }
        setMode(state.mode)

        val profiles = withProfile { queryAll() }
        val groups = querySubscriptionGroups()
        val items = profiles.map {
            val panel = queryPanelInfo(it.uuid)

            SubscriptionItem(it, panel, groups[it.uuid], profileLogoFile(it.uuid, panel))
        }

        setProfiles(items)

        val active = items.firstOrNull { it.profile.active }

        setActiveProfile(active)

        favoritesProfile = active?.profile?.uuid
        setFavorites(favoritesProfile?.let { uiStore.favorites(it) }.orEmpty())

        reloadProxyGroups()
    }

    private var proxyGroupNames: List<String> = emptyList()

    private var offlineGroups: List<PanelGroup> = emptyList()

    private var healthCheckedGroups: List<String> = emptyList()

    private var offlineDelays: Map<String, Int> = emptyMap()

    private var offlineProfile: UUID? = null

    private val offlineSelections: MutableMap<String, String> = mutableMapOf()

    private var favoritesProfile: UUID? = null

    private var serversReadOnly: Boolean = false

    private suspend fun MainDesign.reloadProxyGroups() {
        val names = if (clashRunning) withClash { queryProxyGroupNames(true) } else emptyList()

        if (names.isEmpty()) {
            val direct = clashRunning &&
                withClash { queryTunnelState() }.mode == TunnelState.Mode.Direct

            loadOfflineProxyGroups(readOnly = direct)

            return
        }

        proxyGroupNames = names
        offlineGroups = emptyList()
        serversReadOnly = false

        setProxyGroupNames(names)

        reloadProxyGroup(selectedGroup)

        if (names != healthCheckedGroups) {
            healthCheckedGroups = names

            launch { runHealthCheck() }
        }
    }

    private suspend fun MainDesign.runHealthCheck() {
        if (proxyGroupNames.isEmpty() || serversReadOnly) return

        if (offlineGroups.isNotEmpty()) {
            runOfflineHealthCheck()

            return
        }

        setProxyTesting(true)

        try {
            coroutineScope {
                proxyGroupNames.forEach { group ->
                    launch { withClash { healthCheck(group) } }
                }
            }

            reloadProxyGroup(selectedGroup)
        } catch (e: Exception) {
            Log.w("Health check: $e", e)
        } finally {
            setProxyTesting(false)
        }
    }

    private suspend fun MainDesign.runOfflineHealthCheck() {
        val active = withProfile { queryActive() } ?: return

        setProxyTesting(true)

        try {
            val raw = withClash { testProfileDelays(active.uuid) }

            offlineProfile = active.uuid
            offlineDelays = try {
                Json.Default.decodeFromString(DELAYS_SERIALIZER, raw)
            } catch (e: Exception) {
                Log.w("Parse offline delays: $e", e)

                emptyMap()
            }

            fillOfflineProxyGroup(selectedGroup)
        } catch (e: Exception) {
            Log.w("Offline health check: $e", e)
        } finally {
            setProxyTesting(false)
        }
    }

    private suspend fun MainDesign.loadOfflineProxyGroups(readOnly: Boolean) {
        serversReadOnly = readOnly

        val active = withProfile { queryActive() }
        val panel = active?.let { queryPanelInfo(it.uuid) }
        offlineGroups = panel?.groups.orEmpty()
        proxyGroupNames = offlineGroups.map { it.name }
        healthCheckedGroups = emptyList()

        if (active?.uuid != offlineProfile) {
            offlineProfile = active?.uuid
            offlineDelays = emptyMap()
            offlineSelections.clear()
        }

        proxyGroupNames.forEach { group ->
            val selected = withClash { querySelection(group) }

            if (selected != null) {
                offlineSelections[group] = selected
            } else {
                offlineSelections.remove(group)
            }
        }

        setProxyGroupNames(proxyGroupNames, offline = true, readOnly = readOnly)

        fillOfflineProxyGroup(selectedGroup)
    }

    private suspend fun MainDesign.fillOfflineProxyGroup(index: Int) {
        val group = offlineGroups.getOrNull(index) ?: return

        val readOnly = serversReadOnly

        setProxyGroup(
            index = index,
            now = offlineSelections[group.name].orEmpty(),
            selectable = !readOnly && group.type in OFFLINE_SELECTABLE_GROUPS,
            proxies = group.proxies.map { name ->
                Proxy(
                    name = name,
                    title = name,
                    subtitle = "",
                    type = "",
                    delay = offlineDelays[name] ?: 0,
                    isGroup = false,
                )
            },
        )
    }

    private suspend fun MainDesign.reloadProxyGroup(index: Int) {
        if (offlineGroups.isNotEmpty()) {
            fillOfflineProxyGroup(index)

            return
        }

        val name = proxyGroupNames.getOrNull(index) ?: return
        val group = withClash { queryProxyGroup(name, uiStore.proxySort) }

        setProxyGroup(index, group.now, group.type in SELECTABLE_GROUPS, group.proxies)
    }

    private companion object {
        private val SELECTABLE_GROUPS = setOf("Selector", "URLTest", "Fallback")

        private val OFFLINE_SELECTABLE_GROUPS = setOf("select", "url-test", "fallback")

        private val DELAYS_SERIALIZER = MapSerializer(String.serializer(), Int.serializer())

        private const val LOCAL_PROXY_PORT = 7890
    }

    private var sessionStartedAt: Long = 0
    private var sessionStartedElapsed: Long = 0

    private suspend fun MainDesign.fetchSession() {
        setSessionSeconds(
            SessionClock.seconds(
                startedAt = sessionStartedAt,
                startedElapsed = sessionStartedElapsed,
                nowWall = System.currentTimeMillis(),
                nowElapsed = SystemClock.elapsedRealtime(),
            ),
        )
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTraffic(queryTrafficTotal())
        }
    }

    private fun shouldAskNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return false

        if (uiStore.notificationsAsked)
            return false

        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    private suspend fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return

        try {
            startActivityForResult(RequestPermission(), android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Request notifications: $e", e)
        }
    }

    private suspend fun MainDesign.startClash() {
        if (shouldAskNotifications()) {
            setNotificationPrompt(true)

            return
        }

        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(
                resId = DesignR.string.no_profile_selected,
                duration = ToastDuration.Long,
                actionLabel = DesignR.string.profiles,
                onAction = { launch { selectTab(MainTab.Subscriptions) } },
            )

            return
        }

        setConnecting()

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                } else {
                    setClashRunning(clashRunning)
                }
            }
        } catch (e: Exception) {
            setClashRunning(clashRunning)
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private var pendingUpdate: Updater.Available? = null

    private var awaitingInstallPermission: Boolean = false

    private suspend fun MainDesign.checkUpdate(manual: Boolean) {
        setUpdateChecking(true)

        val available = try {
            UpdatePrompt.check(this@MainActivity, manual, LOCAL_PROXY_PORT)
        } finally {
            setUpdateChecking(false)
        }

        if (available == null) {
            pendingUpdate = null

            if (manual) {
                showToast(DesignR.string.clod_update_none, ToastDuration.Short)
            }

            return
        }

        pendingUpdate = available

        setUpdate(
            UpdateState(
                version = available.manifest.version,
                notes = available.manifest.notes,
            ),
        )
    }

    private suspend fun MainDesign.startUpdate() {
        val available = pendingUpdate ?: return

        if (!ApkInstaller.canInstall(this@MainActivity)) {
            awaitingInstallPermission = true

            showToast(DesignR.string.clod_update_permission, ToastDuration.Long)

            ApkInstaller.requestPermission(this@MainActivity)

            return
        }

        setUpdateProgress(-1f)

        val result = Updater.download(this@MainActivity, available, LOCAL_PROXY_PORT) { received, total ->
            if (total > 0) {
                launch { setUpdateProgress(received.toFloat() / total) }
            }
        }

        setUpdate(null)

        result.fold(
            onSuccess = { apk ->
                runCatching { ApkInstaller.install(this@MainActivity, apk) }.onFailure {
                    Log.w("Install update: $it", it)

                    showExceptionToast(it.message ?: it.toString())
                }
            },
            onFailure = {
                Log.w("Download update: $it", it)

                showExceptionToast(it.message ?: it.toString())
            },
        )
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            launch { design?.showExceptionToast(e) }
        }
    }

    private suspend fun MainDesign.loadVersionName() {
        setAppVersion(
            withContext(Dispatchers.IO) {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            },
        )
    }

    private suspend fun MainDesign.loadAbout() {
        withContext(Dispatchers.IO) {
            val store = AppStore(this@MainActivity)

            setAbout(
                versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                coreVersion = Bridge.nativeCoreVersion()
                    .substringBefore('_')
                    .removePrefix("v"),
                autoCheckUpdate = store.autoCheckUpdate,
                prerelease = store.nightlyChannel,
            )
        }
    }

    private suspend fun updatableProviders(): List<Provider> = runCatching {
        withClash { queryProviders() }
            .filter { it.vehicleType != Provider.VehicleType.Inline }
            .sorted()
    }.getOrDefault(emptyList())

    private suspend fun MainDesign.loadRoutingData() {
        setRoutingData(
            files = GeoData.query(this@MainActivity),
            providers = updatableProviders().map {
                ProviderFileState(name = it.name, updatedAt = it.updatedAt)
            },
        )
    }

    private suspend fun MainDesign.updateRoutingData() {
        setRoutingDataUpdating(true)

        try {
            val geo = GeoData.update(this@MainActivity, LOCAL_PROXY_PORT)

            val providers = runCatching {
                updatableProviders().forEach {
                    withClash { updateProvider(it.type, it.name) }
                }
            }

            geo.fold(
                onSuccess = {
                    if (providers.isSuccess) {
                        showToast(DesignR.string.clod_geo_updated, ToastDuration.Short)
                    } else {
                        Log.w("Update providers: ${providers.exceptionOrNull()}")

                        showToast(DesignR.string.clod_geo_update_failed, ToastDuration.Long)
                    }
                },
                onFailure = {
                    Log.w("Update geo data: $it", it)

                    showToast(DesignR.string.clod_geo_update_failed, ToastDuration.Long)
                },
            )

            loadRoutingData()
        } finally {
            setRoutingDataUpdating(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupShortcuts()
    }

    private fun setupShortcuts() {
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}
