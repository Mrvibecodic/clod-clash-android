package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import android.net.Uri
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
import kotlinx.coroutines.Job
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
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        if (!design.hasProfiles) {
            design.selectTab(MainTab.Subscriptions)
        }

        ProfileUpdates.prune()

        if (ProfileUpdates.running.value.isNotEmpty()) {
            schedulePrune()
        }

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
                            stopRequestedAt = null
                            startRequestedAt = null

                            ProfileUpdates.prune()

                            design.fetch()

                            design.showAddedProfile()

                            design.fetchReliability()

                            if (clashRunning && proxyGroupNames.isNotEmpty() &&
                                SystemClock.elapsedRealtime() - lastHealthCheckAt > HEALTH_STALE_MS
                            ) {
                                launch { design.runHealthCheck(manual = false) }
                            }

                            if (awaitingInstallPermission &&
                                ApkInstaller.canInstall(this@MainActivity)
                            ) {
                                awaitingInstallPermission = false

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
                        Event.ClashStart -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            design.fetch()

                            if (!uiStore.reliabilityAsked) {
                                launch { design.askReliability() }
                            }

                            if (UpdatePrompt.shouldCheckInBackground(this@MainActivity)) {
                                launch { design.checkUpdate(manual = false) }
                            }
                        }
                        Event.ServiceRecreated -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            design.fetch()
                        }
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
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
                            launch { design.checkUpdate(manual = true) }

                        MainDesign.Request.UpdateNow -> design.launchUpdate()
                        MainDesign.Request.UpdateSkip -> {
                            pendingUpdate?.let { UpdatePrompt.skip(this@MainActivity, it.manifest.versionCode) }

                            pendingUpdate = null

                            design.setUpdate(null)
                        }
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
                        is MainDesign.Request.DeleteProfile -> {
                            withProfile { delete(request.profile.uuid) }

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
                            launch { design.updateRoutingData() }
                    }
                }
                if (clashRunning && activityStarted) {
                    ticker.onReceive {
                        design.fetchTraffic()
                        design.fetchSession()

                        ProfileUpdates.prune()

                        design.verifyRunning()
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

    private var iconGroups: Pair<UUID?, List<String>>? = null

    private var healthChecking = false

    private var lastHealthCheckAt = 0L

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

        reloadGroupIcons(names)

        reloadProxyGroup(selectedGroup)

        if (names != healthCheckedGroups) {
            healthCheckedGroups = names

            launch { runHealthCheck(manual = false) }
        }
    }

    private suspend fun MainDesign.runHealthCheck(manual: Boolean) {
        if (proxyGroupNames.isEmpty() || serversReadOnly) return

        lastHealthCheckAt = SystemClock.elapsedRealtime()

        if (healthChecking) return

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
            coroutineScope {
                proxyGroupNames.forEach { group ->
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

            val running = withContext(Dispatchers.IO) {
                StatusClient(this@MainActivity).isRunning()
            }

            if (running)
                Remote.broadcasts.clashRunning = true

            target.setClashRunning(running)
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

    private var pendingUpdate: Updater.Available? = null

    private var awaitingInstallPermission: Boolean = false

    private var updateJob: Job? = null

    private fun MainDesign.launchUpdate() {
        if (updateJob?.isActive == true) return

        updateJob = launch { startUpdate() }
    }

    private suspend fun MainDesign.checkUpdate(manual: Boolean) {
        setUpdateChecking(true)

        val outcome = try {
            UpdatePrompt.check(this@MainActivity, manual, activeLocalProxyPort())
        } finally {
            setUpdateChecking(false)
        }

        val available = when (outcome) {
            is UpdatePrompt.Outcome.Ready -> outcome.available
            UpdatePrompt.Outcome.UpToDate -> {
                pendingUpdate = null

                if (manual) {
                    showToast(DesignR.string.clod_update_none, ToastDuration.Short)
                }

                return
            }
            is UpdatePrompt.Outcome.Failed -> {
                pendingUpdate = null

                if (manual) {
                    showToast(
                        DesignR.string.clod_update_check_failed,
                        ToastDuration.Long,
                        detail = outcome.reason,
                    )
                }

                return
            }
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

        val result = Updater.download(this@MainActivity, available, activeLocalProxyPort()) { received, total ->
            if (total > 0) {
                launch { setUpdateProgress(received.toFloat() / total) }
            }
        }

        setUpdate(null)

        result.fold(
            onSuccess = { apk ->
                try {
                    withContext(Dispatchers.IO) {
                        ApkInstaller.install(this@MainActivity, apk)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Install update: $e", e)

                    showExceptionToast(e.message ?: e.toString())
                }
            },
            onFailure = {
                Log.w("Download update: $it", it)

                showExceptionToast(it.message ?: it.toString())
            },
        )
    }

    private fun schedulePrune() {
        launch {
            delay(ProfileUpdates.TIMEOUT)

            ProfileUpdates.prune()
        }
    }

    private fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)

        if (uri.scheme?.lowercase() !in listOf("http", "https", "tg", "mailto")) {
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

    private suspend fun updatableProviders(): List<Provider> = try {
        withClash { queryProviders() }
            .filter { it.vehicleType != Provider.VehicleType.Inline }
            .sorted()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

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
            val running = clashRunning

            val geo = GeoData.update(this@MainActivity, activeLocalProxyPort(), running)

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
                providersFailed -> showToast(
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

            loadRoutingData()
        } finally {
            setRoutingDataUpdating(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyDynamicShortcuts(uiStore.hideAppIcon)
    }
}
