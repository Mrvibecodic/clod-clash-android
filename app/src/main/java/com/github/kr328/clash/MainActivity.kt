package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.content.Context
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import android.net.Uri
import android.os.RemoteException
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.model.PanelGroup
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeLocalProxyPort
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.compose.screen.ProviderFileState
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.applyDynamicShortcuts
import com.github.kr328.clash.util.GeoData
import com.github.kr328.clash.util.HealthProbes
import com.github.kr328.clash.util.patchSubscriptionGroup
import com.github.kr328.clash.util.ProfileUpdates
import com.github.kr328.clash.service.subscription.reportSubscriptionAlerts
import com.github.kr328.clash.service.util.profileLogoFile
import com.github.kr328.clash.service.util.SessionClock
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.querySubscriptionGroups
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.ServersState
import com.github.kr328.clash.design.compose.screen.SubScreen
import com.github.kr328.clash.design.compose.screen.SubscriptionsState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.compose.screen.UpdateState
import com.github.kr328.clash.update.ApkInstaller
import com.github.kr328.clash.update.UpdatePrompt
import com.github.kr328.clash.update.UpdateTask
import com.github.kr328.clash.util.ServiceUnavailableException
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
        val design = MainDesign(this, restoredState())

        setContentDesign(design)

        design.fetch()

        if (restored == null && !design.hasProfiles) {
            design.selectTab(MainTab.Subscriptions)
        }

        when (design.subScreen) {
            SubScreen.About -> design.request(MainDesign.Request.LoadAbout)
            SubScreen.RoutingData -> design.request(MainDesign.Request.LoadRoutingData)
            null -> Unit
        }

        ProfileUpdates.prune()

        if (ProfileUpdates.running.value.isNotEmpty()) {
            schedulePrune()
        }

        launch {
            ProfileUpdates.running.collect { design.setUpdatingProfiles(it.keys) }
        }

        design.loadVersionName()

        launch {
            UpdateTask.state.collect { design.renderUpdate(it) }
        }

        launch {
            RoutingDataUpdate.state.collect { design.renderRoutingDataUpdate(it) }
        }

        if (UpdatePrompt.shouldCheckInBackground(this)) {
            UpdateTask.check(this, manual = false)
        }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            try {
                select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            ProfileUpdates.prune()

                            design.fetch()

                            design.showAddedProfile()

                            design.fetchReliability()

                            if (design.selectedTab == MainTab.Servers && clashRunning &&
                                proxyGroupNames.isNotEmpty() &&
                                SystemClock.elapsedRealtime() - lastHealthCheckAt > HEALTH_STALE_MS
                            ) {
                                launch { design.runHealthCheck(manual = false) }
                            }

                            if (ApkInstaller.canInstall(this@MainActivity) &&
                                withContext(Dispatchers.IO) {
                                    AppStore(this@MainActivity).awaitingInstallPermission
                                }
                            ) {
                                withContext(Dispatchers.IO) {
                                    AppStore(this@MainActivity).awaitingInstallPermission = false
                                }

                                design.launchUpdate()
                            }
                        }
                        Event.ClashStop -> {
                            stopRequestedAt = null
                            startRequestedAt = null

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
                        Event.ClashStarting -> {
                            stopRequestedAt = null

                            design.setConnecting(startupStage)
                        }
                        Event.ClashStart -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            design.fetch()

                            if (!uiStore.reliabilityAsked) {
                                launch { design.askReliability() }
                            }

                            if (UpdatePrompt.shouldCheckInBackground(this@MainActivity)) {
                                UpdateTask.check(this@MainActivity, manual = false)
                            }
                        }
                        Event.ServiceRecreated -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            design.fetch()
                        }
                        Event.ProfileLoaded -> {
                            healthCheckedGroups = emptyList()

                            design.fetch()
                        }
                        Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                requestStopClash()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.ReloadProxies -> {
                            val started = design.reloadProxyGroups()

                            if (!started && clashRunning && offlineGroups.isEmpty() &&
                                proxyGroupNames.isNotEmpty() &&
                                SystemClock.elapsedRealtime() - lastHealthCheckAt > HEALTH_STALE_MS
                            ) {
                                launch { design.runHealthCheck(manual = false) }
                            }
                        }
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
                        is MainDesign.Request.UrlTest ->
                            launch { design.runHealthCheck(manual = true) }
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
                            UpdateTask.check(this@MainActivity, manual = true)

                        MainDesign.Request.UpdateNow -> design.launchUpdate()
                        MainDesign.Request.UpdateSkip -> {
                            UpdateTask.available?.let {
                                withContext(Dispatchers.IO) {
                                    UpdatePrompt.skip(this@MainActivity, it.manifest.versionCode)
                                }
                            }

                            UpdateTask.dismiss()
                        }
                        MainDesign.Request.UpdateLater -> UpdateTask.dismiss()
                        MainDesign.Request.NewProfile -> launch { addProfile() }
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
                                    schedulePrune()

                                    withProfile(retry = false) { targets.forEach { update(it) } }
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

                                if (!request.profile.imported) {
                                    design.showToast(
                                        DesignR.string.clod_sub_draft,
                                        ToastDuration.Long,
                                    )

                                    return@launch
                                }

                                ProfileUpdates.start(listOf(uuid))
                                schedulePrune()

                                try {
                                    withProfile(retry = false) { update(uuid) }
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
                        is MainDesign.Request.DeleteProfile -> {
                            withProfile(retry = false) { delete(request.profile.uuid) }

                            uiStore.clearFavorites(request.profile.uuid)
                        }
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
                        MainDesign.Request.ReliabilityAllowBattery -> {
                            uiStore.reliabilityAsked = true

                            launch {
                                requestBatteryException()

                                design.fetchReliability()
                            }
                        }
                        MainDesign.Request.ReliabilityOpenVpnSettings -> {
                            uiStore.reliabilityAsked = true

                            openVpnSettings()
                        }
                        MainDesign.Request.ReliabilityDismiss -> {
                            uiStore.reliabilityAsked = true

                            design.setReliability(
                                batteryIgnored = isBatteryIgnored(),
                                alwaysOn = alwaysOnState(),
                                prompt = false,
                            )
                        }
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
                                AppStore(this@MainActivity).prereleaseChannel = request.enabled
                            }

                        MainDesign.Request.LoadRoutingData ->
                            launch { design.loadRoutingData() }

                        MainDesign.Request.UpdateRoutingData ->
                            RoutingDataUpdate.start(this@MainActivity, clashRunning)
                    }
                }
                if (clashRunning && activityStarted) {
                    ticker.onReceive {
                        if (design.selectedTab == MainTab.Home) {
                            design.fetchTraffic()
                            design.fetchSession()
                        }

                        ProfileUpdates.prune()

                        design.verifyRunning()
                    }
                }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServiceUnavailableException) {
                Log.w("Main loop: $e")

                design.showToast(e.message.orEmpty(), ToastDuration.Long)
            } catch (e: RemoteException) {
                Log.w("Main loop: $e", e)

                design.showExceptionToast(e)
            } catch (e: IllegalStateException) {
                Log.w("Main loop: $e", e)

                design.showExceptionToast(e)
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        val status = if (clashRunning) null else withContext(Dispatchers.IO) {
            StatusClient(this@MainActivity).status()
        }

        if (status?.running == true) {
            Remote.broadcasts.clashRunning = true
        }

        if (status?.starting == true) {
            setConnecting(status.stage)

            watchStart()
        } else {
            setClashRunning(clashRunning)
        }

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

    private var healthCheckedGroups: List<String>
        get() = HealthProbes.checkedGroups
        set(value) {
            HealthProbes.checkedGroups = value
        }

    private var iconGroups: Pair<UUID?, List<String>>? = null

    @Volatile
    private var healthChecking = false

    @Volatile
    private var healthCheckRequested = false

    private var lastHealthCheckAt: Long
        get() = HealthProbes.checkedAt
        set(value) {
            HealthProbes.checkedAt = value
        }

    private var offlineDelays: Map<String, Int>
        get() = HealthProbes.offlineDelays
        set(value) {
            HealthProbes.offlineDelays = value
        }

    private var offlineProfile: UUID?
        get() = HealthProbes.offlineProfile
        set(value) {
            HealthProbes.offlineProfile = value
        }

    private val offlineSelections: MutableMap<String, String> = mutableMapOf()

    private var favoritesProfile: UUID? = null

    private var serversReadOnly: Boolean = false

    private suspend fun MainDesign.reloadProxyGroups(): Boolean {
        val names = if (clashRunning) withClash { queryProxyGroupNames(true) } else emptyList()

        if (names.isEmpty()) {
            val direct = clashRunning &&
                withClash { queryTunnelState() }.mode == TunnelState.Mode.Direct

            loadOfflineProxyGroups(readOnly = direct)

            return false
        }

        proxyGroupNames = names
        offlineGroups = emptyList()
        serversReadOnly = false

        setProxyGroupNames(names)

        reloadGroupIcons(names)

        reloadProxyGroup(selectedGroup)

        if (names != healthCheckedGroups && activityStarted) {
            healthCheckedGroups = names

            launch { runHealthCheck(manual = false) }

            return true
        }

        return false
    }

    private suspend fun MainDesign.runHealthCheck(manual: Boolean) {
        if (proxyGroupNames.isEmpty() || serversReadOnly) return

        if (healthChecking) {
            healthCheckRequested = true

            return
        }

        lastHealthCheckAt = SystemClock.elapsedRealtime()

        if (offlineGroups.isNotEmpty()) {
            healthChecking = true

            try {
                runOfflineHealthCheck(manual)
            } finally {
                healthChecking = false
            }

            return
        }

        healthChecking = true

        setProxyTesting(true)

        try {
            val first = proxyGroupNames.getOrNull(selectedGroup)

            if (first != null) {
                withClash { healthCheck(first) }

                reloadProxyGroup(selectedGroup)
            }

            coroutineScope {
                proxyGroupNames.filter { it != first }.forEach { group ->
                    launch { withClash { healthCheck(group) } }
                }
            }

            val delays = reloadProxyGroup(selectedGroup)

            if (manual) {
                notifyDelaysUnavailable(delays)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Health check: $e", e)

            if (manual) {
                showExceptionToast(e)
            }
        } finally {
            healthChecking = false

            setProxyTesting(false)
        }

        if (healthCheckRequested) {
            healthCheckRequested = false

            runHealthCheck(manual = false)
        }
    }

    private suspend fun MainDesign.runOfflineHealthCheck(manual: Boolean) {
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

            if (manual) {
                notifyDelaysUnavailable(offlineDelays.values.toList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Offline health check: $e", e)

            if (manual) {
                showExceptionToast(e)
            }
        } finally {
            setProxyTesting(false)
        }
    }

    private suspend fun MainDesign.loadOfflineProxyGroups(readOnly: Boolean) {
        serversReadOnly = readOnly

        val active = withProfile { queryActive() }
        val panel = active?.let { queryPanelInfo(it.uuid) }
        offlineGroups = panel?.groups.orEmpty().distinctBy { it.name }
        proxyGroupNames = offlineGroups.map { it.name }
        healthCheckedGroups = emptyList()

        iconGroups = null

        setGroupIcons(emptyMap())

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
            proxies = group.proxies.distinct().map { name ->
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

    private suspend fun MainDesign.reloadGroupIcons(names: List<String>) {
        if (!uiStore.showGroupIcons) {
            iconGroups = null

            setGroupIcons(emptyMap())

            return
        }

        val key = favoritesProfile to names

        if (key == iconGroups) return

        if (names.size == 1 && names.first() == GLOBAL_GROUP) {
            iconGroups = key

            setGroupIcons(emptyMap())

            return
        }

        val icons = try {
            withClash { queryProxyGroup(GLOBAL_GROUP, ProxySort.Default) }
                .proxies
                .filter { it.isGroup && it.icon.isNotBlank() && it.name in names }
                .associate { it.name to it.icon }
        } catch (e: Exception) {
            Log.w("Query group icons: $e", e)

            iconGroups = null

            setGroupIcons(emptyMap())

            return
        }

        iconGroups = key

        setGroupIcons(icons)
    }

    private suspend fun MainDesign.notifyDelaysUnavailable(delays: List<Int>) {
        if (delays.isEmpty()) return

        if (delays.any { it in 1 until DELAY_UNKNOWN }) return

        showToast(DesignR.string.clod_delay_unavailable, ToastDuration.Long)
    }

    private suspend fun MainDesign.reloadProxyGroup(index: Int): List<Int> {
        if (offlineGroups.isNotEmpty()) {
            fillOfflineProxyGroup(index)

            return offlineDelays.values.toList()
        }

        val name = proxyGroupNames.getOrNull(index) ?: return emptyList()
        val group = withClash { queryProxyGroup(name, uiStore.proxySort) }

        setProxyGroup(index, group.now, group.type in SELECTABLE_GROUPS, group.proxies)

        return group.proxies.filter { !it.isGroup }.map { it.delay }
    }

    private companion object {
        private const val DELAY_UNKNOWN = 0xffff

        private const val GLOBAL_GROUP = "GLOBAL"

        private val SELECTABLE_GROUPS = setOf("Selector", "URLTest", "Fallback")

        private val OFFLINE_SELECTABLE_GROUPS = setOf("select", "url-test", "fallback")

        private val DELAYS_SERIALIZER = MapSerializer(String.serializer(), Int.serializer())

        private const val HEALTH_STALE_MS = 300_000L

        private const val STOP_FEEDBACK_TIMEOUT_MS = 8_000L

        private const val START_FEEDBACK_TIMEOUT_MS = 45_000L

        private const val RUNNING_PROBE_INTERVAL_MS = 5_000L

        private const val RUNNING_PROBE_MISSES = 2

        private const val KEY_TAB = "tab"

        private const val KEY_SUB_SCREEN = "sub_screen"

        private const val KEY_GROUP = "group"

        private const val KEY_SUB_GROUP = "sub_group"
    }

    private var sessionStartedAt: Long = 0
    private var sessionStartedElapsed: Long = 0
    private var stopRequestedAt: Long? = null
    private var startRequestedAt: Long? = null
    private var runningProbeAt: Long = 0
    private var runningProbeMisses: Int = 0

    private suspend fun addProfile() {
        val result = startActivityForResult(
            ActivityResultContracts.StartActivityForResult(),
            AddProfileActivity::class.intent,
        )

        if (result.resultCode != Activity.RESULT_OK)
            return

        design?.showAddedProfile()
    }

    private suspend fun MainDesign.showAddedProfile() {
        val store = AppStore(this@MainActivity)

        if (!store.addedProfilePending) return

        store.addedProfilePending = false

        val name = store.addedProfileName

        store.addedProfileName = ""

        selectTab(MainTab.Home)

        showToast(
            DesignR.string.clod_sub_added,
            ToastDuration.Long,
            detail = name.takeIf { it.isNotBlank() },
        )
    }

    private fun watchStart() {
        val target = design ?: return

        val now = SystemClock.elapsedRealtime()

        startRequestedAt = now

        launch {
            delay(START_FEEDBACK_TIMEOUT_MS)

            if (startRequestedAt != now)
                return@launch

            startRequestedAt = null

            if (clashRunning)
                return@launch

            val status = withContext(Dispatchers.IO) {
                StatusClient(this@MainActivity).status()
            }

            if (status.running)
                Remote.broadcasts.clashRunning = true

            if (status.starting) {
                target.setConnecting(status.stage)

                watchStart()

                return@launch
            }

            target.setClashRunning(status.running)
        }
    }

    private fun requestStopClash() {
        val target = design ?: return

        val now = SystemClock.elapsedRealtime()

        stopRequestedAt?.let {
            if (now - it < STOP_FEEDBACK_TIMEOUT_MS)
                return
        }

        stopRequestedAt = now

        stopClashService()

        launch {
            target.setDisconnecting()

            delay(STOP_FEEDBACK_TIMEOUT_MS)

            if (stopRequestedAt != now)
                return@launch

            stopRequestedAt = null

            val running = withContext(Dispatchers.IO) {
                StatusClient(this@MainActivity).isRunning()
            }

            target.setClashRunning(running)
        }
    }

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

    private suspend fun MainDesign.verifyRunning() {
        if (!clashRunning)
            return

        val now = SystemClock.elapsedRealtime()

        if (now - runningProbeAt < RUNNING_PROBE_INTERVAL_MS)
            return

        runningProbeAt = now

        val running = withContext(Dispatchers.IO) {
            StatusClient(this@MainActivity).isRunning()
        }

        if (running) {
            runningProbeMisses = 0

            return
        }

        runningProbeMisses++

        if (runningProbeMisses < RUNNING_PROBE_MISSES)
            return

        runningProbeMisses = 0

        Remote.broadcasts.clashRunning = false

        fetch()
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

    private fun isBatteryIgnored(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false

        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private suspend fun alwaysOnState(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return null

        return withContext(Dispatchers.IO) {
            when (ServiceStore(this@MainActivity).vpnAlwaysOn) {
                1 -> true
                0 -> false
                else -> null
            }
        }
    }

    private suspend fun MainDesign.fetchReliability() {
        setReliability(batteryIgnored = isBatteryIgnored(), alwaysOn = alwaysOnState())
    }

    private suspend fun MainDesign.askReliability() {
        setReliability(
            batteryIgnored = isBatteryIgnored(),
            alwaysOn = alwaysOnState(),
            prompt = true,
        )
    }

    private fun startSettings(vararg intents: Intent): Boolean {
        for (intent in intents) {
            if (intent.resolveActivity(packageManager) == null)
                continue

            try {
                startActivity(intent)

                return true
            } catch (e: Exception) {
                Log.w("Open settings ${intent.action}: $e", e)
            }
        }

        return false
    }

    private suspend fun requestBatteryException() {
        if (isBatteryIgnored())
            return

        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))

        if (direct.resolveActivity(packageManager) != null) {
            try {
                startActivityForResult(ActivityResultContracts.StartActivityForResult(), direct)

                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Request battery exception: $e", e)
            }
        }

        if (!startSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            design?.showToast(DesignR.string.clod_reliability_no_settings, ToastDuration.Long)
        }
    }

    private suspend fun openVpnSettings() {
        val opened = startSettings(
            Intent(Settings.ACTION_VPN_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")),
        )

        if (!opened) {
            design?.showToast(DesignR.string.clod_reliability_no_settings, ToastDuration.Long)
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

        watchStart()

        try {
            val vpnRequest = startClashService()

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setClashRunning(clashRunning)
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun MainDesign.launchUpdate() {
        if (UpdateTask.available == null) return

        if (!ApkInstaller.canInstall(this@MainActivity)) {
            withContext(Dispatchers.IO) {
                AppStore(this@MainActivity).awaitingInstallPermission = true
            }

            showToast(DesignR.string.clod_update_permission, ToastDuration.Long)

            ApkInstaller.requestPermission(this@MainActivity)

            return
        }

        UpdateTask.download(this@MainActivity)
    }

    private suspend fun MainDesign.renderUpdate(state: UpdateTask.State) {
        setUpdateChecking(state is UpdateTask.State.Checking)

        when (state) {
            is UpdateTask.State.Available -> setUpdate(
                UpdateState(
                    version = state.available.manifest.version,
                    notes = state.available.manifest.notes,
                ),
            )
            is UpdateTask.State.Downloading -> setUpdate(
                UpdateState(
                    version = state.available.manifest.version,
                    notes = state.available.manifest.notes,
                    downloading = true,
                    progress = state.progress,
                ),
            )
            is UpdateTask.State.UpToDate -> {
                if (state.manual) {
                    showToast(DesignR.string.clod_update_none, ToastDuration.Short)
                }

                UpdateTask.dismiss()
            }
            is UpdateTask.State.CheckFailed -> {
                if (state.manual) {
                    showToast(
                        DesignR.string.clod_update_check_failed,
                        ToastDuration.Long,
                        detail = state.reason,
                    )
                }

                UpdateTask.dismiss()
            }
            is UpdateTask.State.Failed -> {
                showExceptionToast(state.reason)

                UpdateTask.dismiss()
            }
            UpdateTask.State.Idle, is UpdateTask.State.Checking, is UpdateTask.State.Ready ->
                setUpdate(null)
        }
    }

    private fun schedulePrune() {
        launch {
            delay(ProfileUpdates.TIMEOUT)

            ProfileUpdates.prune()
        }
    }

    private fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)

        if (uri.scheme?.lowercase() !in listOf("https", "tg", "mailto")) {
            launch { design?.showToast(DesignR.string.invalid_url, ToastDuration.Long) }

            return
        }

        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
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
                prerelease = store.prereleaseChannel,
            )
        }
    }

    private suspend fun MainDesign.loadRoutingData() {
        setRoutingDataUpdating(RoutingDataUpdate.state.value is RoutingDataUpdate.State.Running)

        setRoutingData(
            files = GeoData.query(this@MainActivity),
            providers = RoutingDataUpdate.updatableProviders().map {
                ProviderFileState(name = it.name, updatedAt = it.updatedAt)
            },
        )
    }

    private suspend fun MainDesign.renderRoutingDataUpdate(state: RoutingDataUpdate.State) {
        setRoutingDataUpdating(state is RoutingDataUpdate.State.Running)

        val outcome = (state as? RoutingDataUpdate.State.Done)?.outcome ?: return

        val geo = outcome.geo

        val reconnect = if (clashRunning) {
            getString(DesignR.string.clod_geo_after_reconnect)
        } else {
            null
        }

        when {
            geo.updated.isEmpty() -> showToast(
                DesignR.string.clod_geo_update_failed,
                ToastDuration.Long,
                detail = geo.failed.joinToString(", ").ifEmpty { null },
            )
            geo.failed.isNotEmpty() -> showToast(
                DesignR.string.clod_geo_update_partial,
                ToastDuration.Long,
                detail = listOfNotNull(geo.failed.joinToString(", ").ifEmpty { null }, reconnect)
                    .joinToString(" · ").ifEmpty { null },
            )
            outcome.providersFailed -> showToast(
                DesignR.string.clod_geo_providers_failed,
                ToastDuration.Long,
                detail = reconnect,
            )
            reconnect != null -> showToast(
                DesignR.string.clod_geo_updated_reconnect,
                ToastDuration.Long,
            )
            else -> showToast(DesignR.string.clod_geo_updated, ToastDuration.Short)
        }

        RoutingDataUpdate.consume()

        loadRoutingData()
    }

    private object RoutingDataUpdate {
        data class Outcome(val geo: GeoData.UpdateResult, val providersFailed: Boolean)

        sealed interface State {
            data object Idle : State
            data object Running : State
            data class Done(val outcome: Outcome) : State
        }

        private val current = MutableStateFlow<State>(State.Idle)

        val state: StateFlow<State> = current

        suspend fun updatableProviders(): List<Provider> = try {
            withClash { queryProviders() }
                .filter { it.vehicleType != Provider.VehicleType.Inline }
                .sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

        fun start(context: Context, running: Boolean) {
            if (current.value is State.Running) return

            val app = context.applicationContext

            current.value = State.Running

            Global.launch {
                try {
                    val geo = GeoData.update(app, app.activeLocalProxyPort(), running)

                    var providersFailed = false

                    updatableProviders().forEach {
                        try {
                            withClash { updateProvider(it.type, it.name) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            providersFailed = true

                            Log.w("Update provider ${it.name}: $e", e)
                        }
                    }

                    current.value = State.Done(Outcome(geo, providersFailed))
                } finally {
                    if (current.value is State.Running) {
                        current.value = State.Idle
                    }
                }
            }
        }

        fun consume() {
            if (current.value is State.Done) {
                current.value = State.Idle
            }
        }
    }

    private fun restoredState(): MainScreenState {
        val bundle = restored ?: return MainScreenState()

        return MainScreenState(
            selectedTab = bundle.getString(KEY_TAB)?.let { MainTab.valueOf(it) } ?: MainTab.Home,
            subScreen = bundle.getString(KEY_SUB_SCREEN)?.let { SubScreen.valueOf(it) },
            servers = ServersState(selected = bundle.getInt(KEY_GROUP)),
            subscriptions = SubscriptionsState(selectedGroup = bundle.getString(KEY_SUB_GROUP)),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val design = design ?: return

        outState.putString(KEY_TAB, design.selectedTab.name)
        outState.putString(KEY_SUB_SCREEN, design.subScreen?.name)
        outState.putInt(KEY_GROUP, design.selectedGroup)
        outState.putString(KEY_SUB_GROUP, design.selectedSubscriptionGroup)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyDynamicShortcuts(uiStore.hideAppIcon)
    }
}
