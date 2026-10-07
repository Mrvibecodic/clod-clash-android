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
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import android.net.Uri
import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxyGroupNames
import com.github.kr328.clash.service.model.PanelGroup
import com.github.kr328.clash.service.store.ServiceSettings
import com.github.kr328.clash.service.util.activeLocalProxyPort
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.compose.screen.AboutState
import com.github.kr328.clash.design.compose.screen.ProviderFileState
import com.github.kr328.clash.design.model.globalRoutingBlocked
import com.github.kr328.clash.design.model.mainGroupOf
import com.github.kr328.clash.design.model.ToggleIntent
import com.github.kr328.clash.design.model.notificationPromptDue
import com.github.kr328.clash.design.model.toggleIntent
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.refreshDynamicShortcuts
import com.github.kr328.clash.util.GeoData
import com.github.kr328.clash.util.HealthProbes
import com.github.kr328.clash.util.loadRouteGroups
import com.github.kr328.clash.util.offlineGroup
import com.github.kr328.clash.util.OfflineDelays
import com.github.kr328.clash.util.patchSubscriptionGroup
import com.github.kr328.clash.util.ProfileUpdates
import com.github.kr328.clash.service.util.profileLogoFile
import com.github.kr328.clash.service.util.SessionClock
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.querySubscriptionGroups
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.SubScreen
import com.github.kr328.clash.design.compose.screen.SubscriptionsState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.compose.screen.UpdateState
import com.github.kr328.clash.update.ApkInstaller
import com.github.kr328.clash.update.UpdatePrompt
import com.github.kr328.clash.update.UpdateTask
import com.github.kr328.clash.util.ServersReload
import com.github.kr328.clash.util.ServiceUnavailableException
import com.github.kr328.clash.util.serversReload
import com.github.kr328.clash.util.HealthCheckRoute
import com.github.kr328.clash.util.healthCheckRoute
import com.github.kr328.clash.util.shouldAutoHealthCheck
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.launchHoldingService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.showNoAppForLink
import com.github.kr328.clash.util.startExternal
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.service.R as ServiceR

class MainActivity : BaseActivity<MainDesign>() {
    override fun onProfileUpdateStarted(uuid: UUID?) {
        super.onProfileUpdateStarted(uuid)

        uuid?.let { ProfileUpdates.start(listOf(it)) }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?, warning: String?) {
        super.onProfileUpdateCompleted(uuid, warning)

        uuid?.let { ProfileUpdates.finish(it) }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        super.onProfileUpdateFailed(uuid, reason)

        uuid?.let { ProfileUpdates.finish(it) }
    }

    override suspend fun main() {
        val design = MainDesign(this, restoredState(), restored?.getString(KEY_GROUP))

        setContentDesign(design)

        // Выбор, не успевший уйти в ядро с прежнего экрана, досылается раньше,
        // чем этот экран что-либо прочитает или применит.
        selectionFlush?.join()

        // Экран, уже видимый к первому чтению, первое ActivityStart заново не читает:
        // берёт его итог. Чтение с ошибкой — исключением, несчитанными группами или
        // режимом — там повторяется; застав старт службы — тоже: ActivityStart
        // снимает ожидание старта, и перечитывание взводит его заново.
        val visible = activityStarted

        var firstFetch: Boolean? = try {
            design.fetch().takeIf {
                visible && panelRunning != null && modeShownFor == favoritesProfile && startRequestedAt == null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Main first fetch: $e", e)

            design.showExceptionToast(e)

            null
        }

        if (restored == null && !design.hasProfiles) {
            design.selectTab(MainTab.Subscriptions)
        }

        when (design.subScreen) {
            SubScreen.About -> design.request(MainDesign.Request.LoadAbout)
            SubScreen.RoutingData -> design.request(MainDesign.Request.LoadRoutingData)
            null -> Unit
        }

        launch {
            ProfileUpdates.running.collect { design.setUpdatingProfiles(it.keys) }
        }

        launch {
            while (isActive) {
                ProfileUpdates.running.first { it.isNotEmpty() }

                while (isActive && ProfileUpdates.running.value.isNotEmpty()) {
                    if (activityStarted) {
                        reconcileUpdatingProfiles()
                    }

                    delay(UPDATES_ACTIVE_POLL_MS)
                }
            }
        }

        design.loadVersionName()

        launch {
            UpdateTask.state.collect { design.renderUpdate(it) }
        }

        launch {
            RoutingDataUpdate.state.collect { design.renderRoutingDataUpdate(it) }
        }

        launch {
            OfflineDelays.state.collect { design.renderOfflineDelays(it) }
        }

        launch {
            RoutingDataUpdate.providerEvents.collect {
                design.setRoutingDataProvider(it.key, it.updating, it.error, it.updatedAt)
            }
        }

        if (UpdatePrompt.shouldCheckInBackground(this)) {
            UpdateTask.check(this, manual = false)
        }

        // Всё, что читает или меняет группы и узлы, исполняется по одному в порядке
        // поступления: так изменения приходят в ядро в порядке нажатий, а индексы
        // групп не уезжают под рукой. Цикл событий только раздаёт эту работу и потому
        // не глохнет, пока служба занята (загрузка конфига держит ядро секундами):
        // нажатия принимаются, переходы по экранам срабатывают сразу.
        val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        worker = launch {
            for (item in work) {
                reportFailures("Main work") { item() }
            }
        }

        // Пачка событий «состояние поменялось» перечитывается одним разом.
        var fetchWanted = false

        fun requestFetch() {
            if (fetchWanted) return

            fetchWanted = true

            work.trySend {
                if (fetchWanted) {
                    fetchWanted = false

                    design.fetch()
                }
            }
        }

        // Трафик и таймер сессии порядка не требуют и не ждут исполнителя.
        launch {
            while (isActive) {
                delay(TICK_MS)

                if (!clashRunning || !activityStarted) continue

                reportFailures("Main tick") {
                    if (design.selectedTab == MainTab.Home) {
                        // url-test и fallback ядро переключает само, без событий;
                        // возврат на Главную перечитывает путь сразу (ReturnHome).
                        // До чтения трафика, чтобы его сбой не съел перечитывание
                        val now = SystemClock.elapsedRealtime()

                        if (now - homeRouteReadAt >= HOME_ROUTE_REFRESH_MS) {
                            homeRouteReadAt = now

                            work.trySend { design.reloadProxyGroup(design.selectedGroup) }
                        }

                        design.fetchTraffic()
                        design.fetchSession()
                    }

                    ProfileUpdates.prune()

                    if (design.verifyRunning()) requestFetch()
                }
            }
        }

        while (isActive) {
            reportFailures("Main loop") {
                select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            // Запрошенная остановка не сбрасывается: служба, ещё не
                            // остановившаяся, не показывается «Подключено»; ожидание
                            // остановки само сверится с ней по таймауту
                            startRequestedAt = null

                            work.trySend {
                                val first = firstFetch.also { firstFetch = null }

                                reconcileUpdatingProfiles()

                                val started = first ?: run {
                                    fetchWanted = false

                                    design.fetch()
                                }

                                design.showAddedProfile()

                                design.fetchReliability()

                                if (design.selectedTab == MainTab.Servers &&
                                    shouldAutoHealthCheck(
                                        clashRunning = clashRunning,
                                        startedByReload = started,
                                        groupsKnown = proxyGroupNames.isNotEmpty(),
                                        readOnly = serversReadOnly,
                                        sinceLastCheckMs = SystemClock.elapsedRealtime() - lastHealthCheckAt,
                                        staleMs = HEALTH_STALE_MS,
                                    )
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
                        }
                        Event.ClashStop -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            offlineDelays = emptyMap()

                            requestFetch()
                        }
                        Event.ClashStarting -> {
                            stopRequestedAt = null

                            design.setConnecting(startupStage)

                            // Старт, узнанный по рассылке, тоже сверяется, если
                            // служба замолчит (гибель посреди старта)
                            watchStart()
                        }
                        Event.ClashStart -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            requestFetch()

                            if (!uiStore.reliabilityAsked) {
                                launch { design.fetchReliability(prompt = true) }
                            }

                            if (UpdatePrompt.shouldCheckInBackground(this@MainActivity)) {
                                UpdateTask.check(this@MainActivity, manual = false)
                            }
                        }
                        Event.ServiceRecreated -> {
                            stopRequestedAt = null
                            startRequestedAt = null

                            requestFetch()
                        }
                        Event.ProfileLoaded -> {
                            healthCheckedGroups = emptyList()

                            // При запуске подписка загружается до готовности: экран
                            // перечитает ClashStart (или ClashStop), а чтение сейчас
                            // застало бы ядро ещё не запущенным.
                            if (!clashActive || clashRunning) requestFetch()
                        }
                        is Event.ProfileChanged -> {
                            val uuid = it.uuid

                            // Только список подписок, когда группы и режим на экране от
                            // неё не меняются: у запущенного ядра загрузку объявит
                            // ProfileLoaded (или остановка), а чужая подписка показанной
                            // не касается. Подписка не названа (сбой загрузки) — целиком.
                            if (uuid == null) requestFetch() else work.trySend {
                                if (clashRunning || uuid != favoritesProfile) {
                                    val shown = uuid == favoritesProfile

                                    design.fetchProfiles()

                                    // Замок режима и режим из панели приходят с самой
                                    // подпиской — показываются сразу, не дожидаясь загрузки
                                    // (сменилась активная — режим уже прочитало полное чтение)
                                    if (clashRunning && shown && uuid == favoritesProfile) design.fetchMode(uuid)
                                } else {
                                    requestFetch()
                                }
                            }
                        }
                        is Event.FreezeMarksChanged -> {
                            val uuid = it.uuid

                            // На экран — пометки только показанной подписки
                            work.trySend {
                                if (uuid == null || uuid == favoritesProfile) {
                                    design.fetchFreezeMarks(favoritesProfile)
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        MainDesign.Request.ToggleStatus -> when (toggleIntent(design.status)) {
                            ToggleIntent.Start -> startInOrder(work)
                            // Стоп — только рассылка службе, очередь ему не нужна.
                            ToggleIntent.Stop -> requestStopClash()
                            ToggleIntent.Ignore -> Unit
                        }
                        MainDesign.Request.ReloadProxies -> work.trySend {
                            val liveGroups = offlineGroups.isEmpty() && proxyGroupNames.isNotEmpty()

                            val started = when (serversReload(panelRunning == clashRunning, liveGroups)) {
                                ServersReload.Panel -> design.reloadProxyGroups()
                                ServersReload.SelectedGroup -> {
                                    design.reloadProxyGroup(design.selectedGroup, route = false)

                                    design.reportGlobalRoutingBlocked(proxyGroupNames)

                                    false
                                }
                                ServersReload.Nothing -> false
                            }

                            if (
                                shouldAutoHealthCheck(
                                    clashRunning = clashRunning,
                                    startedByReload = started,
                                    groupsKnown = proxyGroupNames.isNotEmpty(),
                                    readOnly = serversReadOnly,
                                    sinceLastCheckMs = SystemClock.elapsedRealtime() - lastHealthCheckAt,
                                    staleMs = HEALTH_STALE_MS,
                                )
                            ) {
                                launch { design.runHealthCheck(manual = false) }
                            }
                        }
                        MainDesign.Request.ReturnHome -> work.trySend {
                            if (clashRunning) {
                                homeRouteReadAt = SystemClock.elapsedRealtime()

                                design.reloadProxyGroup(design.selectedGroup)
                            }
                        }
                        is MainDesign.Request.ReloadGroup -> work.trySend {
                            proxyGroupNames.indexOf(request.group).takeIf { it >= 0 }
                                ?.let { design.reloadProxyGroup(it, route = false) }
                        }
                        is MainDesign.Request.SelectProxy -> {
                            val group = request.group

                            // Выбор — подписке, чей список на экране в момент нажатия
                            val owner = groupsShownFor

                            if (group in proxyGroupNames && !serversReadOnly && owner != null) {
                                design.markProxySelected(group, request.name)

                                val slot = Slot(owner, group)

                                if (pendingSelections.put(slot, request.name) == null) {
                                    work.trySend { design.applySelection(slot) }
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
                        is MainDesign.Request.DismissPromo ->
                            uiStore.setDismissedPromo(request.profile, request.fingerprint)
                        is MainDesign.Request.PatchMode -> work.trySend {
                            // Режим выбирается для подписки, что на экране: и замок
                            // проверяется, и выбор пишется ей, а не «активной» службы.
                            val profile = favoritesProfile ?: return@trySend

                            val locked = withClash { queryProfileMode(profile) }.source ==
                                ProfileMode.Source.Locked

                            if (locked) {
                                design.showToast(
                                    DesignR.string.clod_mode_locked_toast,
                                    ToastDuration.Long,
                                )
                            } else {
                                withClash { setProfileMode(profile, request.mode) }

                                design.showToast(
                                    if (clashRunning) {
                                        DesignR.string.clod_mode_saved
                                    } else {
                                        DesignR.string.clod_mode_saved_offline
                                    },
                                    ToastDuration.Short,
                                )

                                // Выбор режима меняет показ режима и у запущенного ядра
                                // список групп; подписки и пометки он не трогает.
                                design.fetchMode(profile)

                                if (clashRunning) design.reloadProxyGroups()
                            }
                        }
                        is MainDesign.Request.OpenUrl -> openExternalUrl(request.url)
                        MainDesign.Request.CheckUpdate ->
                            if (UpdateTask.state.value is UpdateTask.State.Checking) {
                                UpdateTask.cancel()
                            } else {
                                UpdateTask.check(this@MainActivity, manual = true)
                            }

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
                        MainDesign.Request.UpdateCancel -> UpdateTask.cancel()
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

                                    withProfile(retry = false) { targets.forEach { update(it) } }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    targets.forEach { ProfileUpdates.finish(it) }

                                    design.showExceptionToast(e, ServiceR.string.update_failure)
                                }
                            }
                        }
                        is MainDesign.Request.ActivateProfile -> work.trySend {
                            val profile = request.profile

                            if (profile.imported) {
                                withProfile { setActive(profile) }
                            } else {
                                design.showToast(
                                    resId = DesignR.string.clod_sub_draft_activate,
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
                                    withProfile(retry = false) { update(uuid) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    ProfileUpdates.finish(uuid)

                                    design.showExceptionToast(e, ServiceR.string.update_failure)
                                }
                            }
                        }
                        is MainDesign.Request.EditProfile ->
                            startActivity(
                                PropertiesActivity::class.intent.setUUID(request.profile.uuid),
                            )
                        is MainDesign.Request.DeleteProfile -> work.trySend {
                            withProfile(retry = false) { delete(request.profile.uuid) }

                            uiStore.clearFavorites(request.profile.uuid)
                            uiStore.clearDismissedPromo(request.profile.uuid)
                            patchSubscriptionGroup(request.profile.uuid, null)
                        }
                        MainDesign.Request.AllowNotifications -> {
                            design.setNotificationPrompt(false)

                            launch {
                                requestNotificationPermission()

                                uiStore.notificationsRequested = true

                                startInOrder(work)
                            }
                        }
                        MainDesign.Request.SkipNotifications -> {
                            uiStore.notificationsSnoozedAt = System.currentTimeMillis()
                            uiStore.notificationsSnoozes += 1

                            design.setNotificationPrompt(false)

                            startInOrder(work)
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

                            design.fetchReliability(prompt = false)
                        }
                        is MainDesign.Request.SetSubscriptionGroup -> work.trySend {
                            patchSubscriptionGroup(request.profile.uuid, request.group)

                            design.fetchProfiles()
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
                        MainDesign.Request.LoadAbout -> launch {
                            try {
                                design.loadCoreVersion()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w("Load core version: $e", e)
                            }
                        }
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

                        is MainDesign.Request.UpdateRoutingDataProvider ->
                            RoutingDataUpdate.startProvider(request.key)
                    }
                }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch(known: List<Profile>? = null): Boolean {
        panelRunning = null

        val epoch = Remote.broadcasts.epoch
        val running = clashRunning

        val notes = withContext(Dispatchers.IO) {
            StatusClient(this@MainActivity).status()
        }

        val status = if (running) null else notes

        // Ответ свежее рассылок, только если их не было во время чтения
        val stage = if (status != null && Remote.broadcasts.epoch == epoch) status.stage else startupStage

        if (status != null) {
            Remote.broadcasts.apply(status, epoch)
        }

        // Состояние — по рассылкам, а не по своему ответу: старт, о котором
        // рассылка пришла во время чтения, не сменяется на «Отключено», а
        // запрошенная остановка работающей службы — на «Подключено»
        if (clashActive && !clashRunning) {
            setConnecting(stage)

            watchStart()
        } else if (stopRequestedAt == null || !clashRunning) {
            setClashRunning(clashRunning)
        }

        setSessionNotes(notes.restartedBySystem, notes.systemProxyRefused)

        // Отметку старта сессии служба отдаёт тем же ответом о состоянии
        sessionStartedAt = if (clashRunning) notes.startedAt else 0L
        sessionStartedElapsed = if (clashRunning) notes.startedElapsed else 0L

        fetchSession()

        val profiles = known ?: withProfile { queryAll() }
        val activeUuid = profiles.firstOrNull { it.active }?.uuid

        // Режим спрашивается о той подписке, что будет показана, и до первой
        // перерисовки: отказ службы здесь оставляет экран целиком прежним. Сбой
        // запроса — не «режим из шаблона»: показ остаётся прежним, но только для
        // той же подписки — замок и режим чужой показывать нельзя.
        val mode = if (activeUuid == null) ProfileMode() else try {
            withClash { queryProfileMode(activeUuid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.w("Query profile mode: $e", e)

            null
        }

        showProfiles(profiles)

        if (mode != null) {
            setMode(mode)

            modeShownFor = activeUuid
        } else if (modeShownFor != activeUuid) {
            setMode(ProfileMode())

            modeShownFor = null
        }

        setFavorites(favoritesProfile?.let { uiStore.favorites(it) }.orEmpty())
        fetchFreezeMarks(favoritesProfile)
        setDismissedPromo(favoritesProfile, favoritesProfile?.let { uiStore.dismissedPromo(it) }.orEmpty())

        return reloadProxyGroups()
    }

    private suspend fun MainDesign.showProfiles(profiles: List<Profile>) {
        val groups = querySubscriptionGroups()
        val items = profiles.map {
            val panel = queryPanelInfo(it.uuid)

            SubscriptionItem(it, panel, groups[it.uuid], profileLogoFile(it.uuid, panel))
        }

        setProfiles(items)

        val active = items.firstOrNull { it.profile.active }

        setActiveProfile(active)

        shownActive = active
    }

    // Режим показанной подписки. Сбой запроса оставляет прежний показ — как в
    // полном чтении экрана
    private suspend fun MainDesign.fetchMode(uuid: UUID) {
        val mode = try {
            withClash { queryProfileMode(uuid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.w("Query profile mode: $e", e)

            return
        }

        if (uuid != favoritesProfile) return

        setMode(mode)

        modeShownFor = uuid
    }

    // Пока активна подписка, показанная на экране, её группы, режим и пометки от
    // перечитывания списка не меняются: читается только он. Сменилась — экран целиком.
    private suspend fun MainDesign.fetchProfiles() {
        val profiles = withProfile { queryAll() }

        if (profiles.firstOrNull { it.active }?.uuid == favoritesProfile) {
            showProfiles(profiles)
        } else {
            fetch(profiles)
        }
    }

    private var proxyGroupNames: List<String> = emptyList()

    private var offlineGroups: List<PanelGroup> = emptyList()

    private var offlineHides: (String) -> Boolean = { false }

    private var mainGroup: String? = null

    private var panelRunning: Boolean? = null

    private var healthCheckedGroups: List<String>
        get() = HealthProbes.checkedGroups
        set(value) {
            HealthProbes.checkedGroups = value
        }

    @Volatile
    private var healthChecking = false

    @Volatile
    private var healthCheckRequested = false
    private var healthCheckRequestedManually = false

    private var homeRouteReadAt = SystemClock.elapsedRealtime()

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

    // Последний выбранный в группе узел, ещё не применённый: из серии нажатий,
    // пришедших, пока ядро занято, применяется последнее, а до тех пор любая
    // перерисовка группы показывает его, а не прежний.
    // Выбор принадлежит подписке, на чьём списке его сделали: одноимённые
    // группы разных подписок не вытесняют выбор друг друга.
    private val pendingSelections: MutableMap<Slot, String> = mutableMapOf()

    // Группа и подписка, на чьём списке её выбрали
    private data class Slot(val owner: UUID, val group: String)

    // Ещё не применённый выбор группы нынешнего списка
    private fun pendingOf(group: String): String? =
        groupsShownFor?.let { pendingSelections[Slot(it, group)] }

    private var worker: Job? = null

    // Выбор, который прямо сейчас уходит в ядро: отмена экрана может оборвать его до
    // отправки. Досылка повторяет его, только если он не дошёл: повторный выбор в ядре
    // заново рвёт соединения группы.
    private class Sending(val slot: Slot, val name: String) {
        @Volatile
        var delivered = false
    }

    private var sendingSelection: Sending? = null

    // Экран закрывается или пересоздаётся (поворот, тема), а отмеченный на нём узел ещё
    // не ушёл в ядро: досылаем в фоне, после обращения, которое уже в пути. В onStop —
    // до того, как приложение, уходя с экрана, отпустит службу, и чтобы новый экран,
    // открытый сразу после закрытия, уже ждал досылку; onDestroy подбирает остаток.
    override fun onStop() {
        if (isFinishing || isChangingConfigurations) handOffSelections()

        super.onStop()
    }

    override fun onDestroy() {
        handOffSelections()

        super.onDestroy()
    }

    private fun handOffSelections() {
        val sending = sendingSelection
        val left = pendingSelections.toMap()

        sendingSelection = null
        pendingSelections.clear()

        if ((left.isEmpty() && sending == null) || serversReadOnly) return

        val previous = worker
        val prior = selectionFlush

        // Держит службу, пока досылка не закончится (служба сама отпустится не позже
        // чем через 90 с — досылка укладывается в минуту или бросается).
        selectionFlush = launchHoldingService {
            try {
                prior?.join()
                previous?.join()

                // Прежняя очередь остановлена: теперь известно, дошёл ли выбор, что был в пути.
                val all = LinkedHashMap<Slot, String>()

                sending?.takeUnless { it.delivered }?.let { all[it.slot] = it.name }
                all.putAll(left)

                withTimeoutOrNull(SELECTION_HANDOFF_MS) {
                    // Выбор принадлежит подписке, в которой его сделали; в ядро ли он
                    // уйдёт, решает ядро по тому, что держит при отправке.
                    for ((slot, name) in all) {
                        val group = slot.group

                        try {
                            withClash(retry = false) {
                                select(slot.owner, group, name)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("Apply selection $name for $group: $e", e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Hand off selections: $e", e)
            }
        }
    }

    // Активная подписка в том виде, в каком показана: от неё зависят избранное,
    // режим, пометки и панель из подписки.
    private var shownActive: SubscriptionItem? = null

    private val favoritesProfile: UUID?
        get() = shownActive?.profile?.uuid

    private var modeShownFor: UUID? = null

    private var groupsShownFor: UUID? = null

    private var serversReadOnly: Boolean = false

    // Список — из файла подписки, которую туннель ещё загружает
    private var serversLoading: Boolean = false

    private var globalSelection: String? = null

    private var globalBlockedReported: Boolean = false

    private suspend fun MainDesign.reloadProxyGroups(): Boolean {
        val running = clashRunning
        val snapshot = if (running) queryLiveGroupNames() ?: return false else ProxyGroupNames()
        val loaded = snapshot.profile?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val names = snapshot.names

        setAllGroupsOnHome(uiStore.showAllGroupsOnHome)

        // Ядро держит другую подписку (активную сменили, загрузка идёт) или меняет
        // конфиг прямо сейчас (подписи нет): живые группы с избранным, пометками
        // и скрытием показанной смешали бы подписки. До загрузки — группы
        // показанной из её файла, как без туннеля; загрузка перечитает экран
        if (running && loaded != favoritesProfile) {
            globalSelection = null

            loadOfflineProxyGroups(readOnly = false, loading = true)

            panelRunning = running

            return false
        }

        if (names.isEmpty()) {
            globalSelection = null

            loadOfflineProxyGroups(readOnly = snapshot.direct)

            panelRunning = running

            return false
        }

        proxyGroupNames = names
        offlineGroups = emptyList()
        serversReadOnly = false
        serversLoading = false
        mainGroup = mainGroupOf(names, snapshot.main)
        groupsShownFor = loaded

        setProxyGroupNames(names, main = mainGroup)

        setGroupIcons(if (uiStore.showGroupIcons) snapshot.icons else emptyMap())

        reloadProxyGroup(selectedGroup)

        reportGlobalRoutingBlocked(names)

        panelRunning = running

        if (names != healthCheckedGroups && activityStarted) {
            healthCheckedGroups = names

            launch { runHealthCheck(manual = false) }

            return true
        }

        return false
    }

    private suspend fun MainDesign.runHealthCheck(manual: Boolean, force: Boolean = manual) {
        if (proxyGroupNames.isEmpty() || serversReadOnly) return

        // Пока туннель загружает подписку, замер её узлов напрямую устарел бы
        // сразу после загрузки: живой замер пойдёт сам, когда придут её группы
        if (serversLoading) {
            if (manual) showToast(DesignR.string.clod_test_loading, ToastDuration.Long)

            return
        }

        val route = healthCheckRoute(offlinePanel = offlineGroups.isNotEmpty(), manual = manual)

        if (route == HealthCheckRoute.Skip) return

        if (healthChecking) {
            healthCheckRequested = true

            // Спиннер гаснет по видимой группе, а проверка идёт дальше: без
            // очереди повторное нажатие не делало бы вообще ничего.
            if (manual) {
                healthCheckRequestedManually = true

                setProxyTesting(true)
            }

            return
        }

        // Штамп до замера не даёт возврату на экран поставить второй круг в
        // очередь; замер, упавший с ошибкой, его снимает
        lastHealthCheckAt = SystemClock.elapsedRealtime()

        if (route == HealthCheckRoute.Offline) {
            startOfflineHealthCheck()

            return
        }

        healthChecking = true

        setProxyTesting(true)

        try {
            val first = proxyGroupNames.getOrNull(selectedGroup)

            if (first != null) {
                withClash { healthCheck(first) }
            }

            val others = proxyGroupNames.filter { it != first }

            // Без других групп видимую перечитывает итог ниже
            if (others.isNotEmpty()) {
                if (first != null) {
                    loadProxyGroup(selectedGroup)

                    // Видимая группа готова; остальные дозамеряются в фоне, пока
                    // healthChecking всё ещё не пускает второй круг
                    setProxyTesting(false)
                }

                withClash { healthCheckGroups(others, listOfNotNull(first), force) }
            }

            val delays = reloadProxyGroup(selectedGroup)

            if (manual) {
                notifyDelaysUnavailable(delays)
            }
        } catch (e: CancellationException) {
            lastHealthCheckAt = 0

            throw e
        } catch (e: Exception) {
            Log.w("Health check: $e", e)

            lastHealthCheckAt = 0

            if (manual) {
                showExceptionToast(e, DesignR.string.clod_delay_failed)
            }
        } finally {
            healthChecking = false

            setProxyTesting(false)
        }

        drainQueuedHealthCheck()
    }

    private suspend fun MainDesign.drainQueuedHealthCheck() {
        if (!healthCheckRequested) return

        healthCheckRequested = false

        val queuedManually = healthCheckRequestedManually

        healthCheckRequestedManually = false

        // Нажатие во время круга: только что промеренные живые узлы не перемеряются
        runHealthCheck(manual = queuedManually, force = false)
    }

    // clod:freeze — пометки «режется» / «не отвечает» считает служба; экран только читает
    private suspend fun MainDesign.fetchFreezeMarks(uuid: UUID?) {
        setFreezeMarks(uuid?.let { withClash { queryFreezeMarks(it) } }.orEmpty())
    }

    private suspend fun MainDesign.startOfflineHealthCheck() {
        if (OfflineDelays.running) return

        val active = withProfile { queryActive() } ?: return

        val total = offlineGroups.flatMap { it.proxies }.filterNot(offlineHides).distinct().size

        OfflineDelays.start(active.uuid, total)
    }

    private suspend fun MainDesign.renderOfflineDelays(update: OfflineDelays.State) {
        when (update) {
            is OfflineDelays.State.Idle -> Unit
            is OfflineDelays.State.Running -> setProxyTesting(true, update.total)
            is OfflineDelays.State.Done -> {
                if (withProfile { queryActive() }?.uuid == update.profile) {
                    offlineProfile = update.profile
                    offlineDelays = update.delays

                    fillOfflineProxyGroup(selectedGroup)

                    if (update.error != null) {
                        showExceptionToast(update.error, DesignR.string.clod_delay_failed)
                    } else {
                        notifyDelaysUnavailable(update.delays.values.toList())
                    }
                }

                setProxyTesting(false)

                OfflineDelays.consume()
            }
        }
    }

    // Живой список групп принадлежит подписке, загруженной в ядро, а не выбранной:
    // ядро отдаёт её вместе со списком. Подписка в ядре по ответу службы нужна только
    // при сбое запроса — понять, чей список сейчас на экране.
    private suspend fun loadedProfile(): UUID? = withContext(Dispatchers.IO) {
        StatusClient(this@MainActivity).status().uuid
    }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    // Сбой запроса при живом ядре — не «групп нет»: живой список той же подписки
    // остаётся как был; офлайн-список (выбор в нём к ядру не применяется) и список
    // другой подписки убираются, причина — в уведомлении. panelRunning не
    // выставляется, и следующая перезагрузка панели повторит запрос.
    private suspend fun MainDesign.queryLiveGroupNames(): ProxyGroupNames? = try {
        withClash { queryProxyGroupNames(true) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ServiceUnavailableException) {
        throw e
    } catch (e: Exception) {
        Log.w("Query proxy group names: $e", e)

        if (offlineGroups.isNotEmpty() || groupsShownFor != loadedProfile()) {
            offlineGroups = emptyList()
            proxyGroupNames = emptyList()
            mainGroup = null

            setProxyGroupNames(emptyList(), main = null)
        }

        showExceptionToast(e, DesignR.string.clod_servers_query_failed)

        null
    }

    private suspend fun MainDesign.loadOfflineProxyGroups(readOnly: Boolean, loading: Boolean = false) {
        serversReadOnly = readOnly
        serversLoading = loading

        // Панель той подписки, что показана активной, — без повторного чтения
        val active = favoritesProfile
        val panel = shownActive?.panel
        offlineGroups = panel?.groups.orEmpty().distinctBy { it.name }
        offlineHides = { panel?.hides(it) == true }
        proxyGroupNames = offlineGroups.map { it.name }
        mainGroup = mainGroupOf(proxyGroupNames, panel?.main)
        healthCheckedGroups = emptyList()
        groupsShownFor = active

        setGroupIcons(emptyMap())

        if (active != offlineProfile) {
            offlineProfile = active
            offlineDelays = emptyMap()
            offlineSelections.clear()
        }

        val saved = if (proxyGroupNames.isEmpty()) emptyMap() else withClash { querySelections() }

        proxyGroupNames.forEach { group ->
            val selected = saved[group]

            if (selected != null) {
                offlineSelections[group] = selected
            } else {
                offlineSelections.remove(group)
            }
        }

        setProxyGroupNames(proxyGroupNames, offline = true, loading = loading, readOnly = readOnly, main = mainGroup)

        fillOfflineProxyGroup(selectedGroup)
    }

    private suspend fun MainDesign.fillOfflineProxyGroup(index: Int) {
        val now = fillOfflineGroup(index)

        loadRouteGroups(proxyGroupNames, homeGroups(), index, now) { fillOfflineGroup(it) }
    }

    private suspend fun MainDesign.fillOfflineGroup(index: Int): String? {
        val group = offlineGroups.getOrNull(index) ?: return null

        val readOnly = serversReadOnly
        val saved = pendingOf(group.name) ?: offlineSelections[group.name]
        val shown = offlineGroup(group, saved?.takeIf { it.isNotEmpty() }, offlineHides)

        setProxyGroup(
            index = index,
            now = shown.now,
            selectable = !readOnly && group.type in OFFLINE_SELECTABLE_GROUPS,
            pinned = if (!readOnly && group.type in OFFLINE_PINNABLE_GROUPS) saved.orEmpty() else null,
            proxies = shown.proxies.map { name ->
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

        return shown.now
    }

    private suspend fun MainDesign.notifyDelaysUnavailable(delays: List<Int>) {
        // 0 — узел не промерен: судить можно только по промеренным
        if (delays.none { it >= DELAY_UNKNOWN } || delays.any { it in 1 until DELAY_UNKNOWN }) return

        showToast(DesignR.string.clod_delay_unavailable, ToastDuration.Long)
    }

    private suspend fun MainDesign.reportGlobalRoutingBlocked(names: List<String>) {
        if (!globalRoutingBlocked(names, globalSelection)) {
            globalBlockedReported = false

            return
        }

        if (globalBlockedReported) return

        globalBlockedReported = true

        showToast(
            resId = DesignR.string.clod_global_nothing_to_use,
            duration = ToastDuration.Long,
            actionLabel = DesignR.string.clod_tab_subscriptions,
            onAction = { launch { selectTab(MainTab.Subscriptions) } },
        )
    }

    // route — заодно перечитать путь Главной по выбранным узлам. Без него — показ
    // одной группы; путь Главной освежают её тик и возврат на неё.
    private suspend fun MainDesign.reloadProxyGroup(index: Int, route: Boolean = true): List<Int> {
        if (offlineGroups.isNotEmpty()) {
            fillOfflineProxyGroup(index)

            return offlineDelays.values.toList()
        }

        val group = loadProxyGroup(index)

        if (route) {
            loadRouteGroups(proxyGroupNames, homeGroups(), index, group?.now) { loadProxyGroup(it)?.now }
        }

        return group?.proxies.orEmpty().filter { !it.isGroup }.map { it.delay }
    }

    private suspend fun MainDesign.loadProxyGroup(index: Int): ProxyGroup? {
        val name = proxyGroupNames.getOrNull(index) ?: return null
        val group = withClash { queryProxyGroup(name) }

        if (name == GLOBAL_GROUP) {
            globalSelection = group.now
        }

        // Пока шёл запрос, список групп мог смениться — тогда слот уже чужой
        if (proxyGroupNames.getOrNull(index) == name) {
            val pending = pendingOf(name)

            setProxyGroup(
                index,
                pending?.takeIf { it.isNotEmpty() } ?: group.now,
                group.type in SELECTABLE_GROUPS,
                group.proxies,
                pinned = if (!serversReadOnly && group.type in PINNABLE_GROUPS) pending ?: group.pinned else null,
            )
        }

        return group
    }

    private fun homeGroups(): List<String> =
        if (uiStore.showAllGroupsOnHome) proxyGroupNames else listOfNotNull(mainGroup)

    private companion object {
        private var selectionFlush: Job? = null

        private val SELECTION_HANDOFF_MS = TimeUnit.SECONDS.toMillis(60)

        private const val DELAY_UNKNOWN = 0xffff

        private val HOME_ROUTE_REFRESH_MS = TimeUnit.SECONDS.toMillis(60)

        private val TICK_MS = TimeUnit.SECONDS.toMillis(1)

        private val UPDATES_ACTIVE_POLL_MS = TimeUnit.SECONDS.toMillis(3)

        private const val GLOBAL_GROUP = "GLOBAL"

        private val SELECTABLE_GROUPS = setOf("Selector", "URLTest", "Fallback")

        private val OFFLINE_SELECTABLE_GROUPS = setOf("select", "url-test", "fallback")

        // Группы, что выбирают узел сами: выбор человека в них — закрепление
        private val PINNABLE_GROUPS = setOf("URLTest", "Fallback")

        private val OFFLINE_PINNABLE_GROUPS = setOf("url-test", "fallback")


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

    private suspend fun reconcileUpdatingProfiles() {
        ProfileUpdates.reconcile(
            withContext(Dispatchers.IO) {
                StatusClient(this@MainActivity).updatingProfiles()
            },
        )
    }

    private suspend fun MainDesign.showAddedProfile() {
        val store = AppStore(this@MainActivity)

        val providers = store.profileProvidersFailed

        if (providers.isNotBlank()) {
            store.profileProvidersFailed = ""
        }

        if (!store.addedProfilePending) {
            if (providers.isNotBlank()) {
                showToast(
                    DesignR.string.clod_providers_failed_plural,
                    ToastDuration.Long,
                    detail = providers,
                    kind = NoticeKind.Error,
                )
            }

            return
        }

        store.addedProfilePending = false

        val name = store.addedProfileName

        store.addedProfileName = ""

        val channel = getString(
            if (store.addedProfileSecure) DesignR.string.clod_sub_channel_on else DesignR.string.clod_sub_channel_off,
        )

        selectTab(MainTab.Home)

        if (providers.isNotBlank()) {
            showToast(
                DesignR.string.clod_sub_added_partial,
                ToastDuration.Long,
                detail = listOfNotNull(name.takeIf { it.isNotBlank() }, channel, providers).joinToString(" · "),
            )

            return
        }

        if (name.isNotBlank()) {
            showToast(getString(DesignR.string.clod_sub_added_named, name), ToastDuration.Long, detail = channel)
        } else {
            showToast(DesignR.string.clod_sub_added, ToastDuration.Long, detail = channel)
        }
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

            val epoch = Remote.broadcasts.epoch

            val status = withContext(Dispatchers.IO) {
                StatusClient(this@MainActivity).status()
            }

            Remote.broadcasts.apply(status, epoch)

            if (status.starting) {
                target.setConnecting(status.stage)

                watchStart()

                return@launch
            }

            target.setClashRunning(status.running)
        }
    }

    // Старт идёт в общей очереди экрана: не обгоняет выбор узла, сохраняемый для
    // незапущенного ядра. Если к своему ходу он уже не нужен (ядро запущено или
    // запускается по прошлому нажатию) — пропускается.
    private fun startInOrder(work: SendChannel<suspend () -> Unit>) {
        work.trySend {
            val target = design ?: return@trySend

            if (toggleIntent(target.status) == ToggleIntent.Start) target.startClash()
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

        startRequestedAt = null

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

    // true — служба пропала без сообщения, и экран надо перечитать.
    private suspend fun MainDesign.verifyRunning(): Boolean {
        if (!clashRunning)
            return false

        val now = SystemClock.elapsedRealtime()

        if (now - runningProbeAt < RUNNING_PROBE_INTERVAL_MS)
            return false

        runningProbeAt = now

        val epoch = Remote.broadcasts.epoch

        val running = withContext(Dispatchers.IO) {
            StatusClient(this@MainActivity).isRunning()
        }

        if (running) {
            runningProbeMisses = 0

            return false
        }

        runningProbeMisses++

        if (runningProbeMisses < RUNNING_PROBE_MISSES)
            return false

        runningProbeMisses = 0

        Remote.broadcasts.apply(null, epoch)

        return true
    }

    // Отказ службы приходит через binder любым из разрешённых Parcel типов
    // (IllegalArgument, IllegalState, Security, NullPointer…): экран показывает
    // причину и живёт дальше, а не падает — обработчик области активности
    // пропускает только ServiceUnavailableException.
    private suspend fun reportFailures(where: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            Log.w("$where: $e")

            design?.showExceptionToast(e)
        } catch (e: Exception) {
            Log.w("$where: $e", e)

            design?.showExceptionToast(e)
        }
    }

    // Узел уже отмечен на экране в момент нажатия. Здесь выбор уходит в ядро (или
    // запоминается для незапущенного), затем группа перерисовывается из источника:
    // при отказе отметка откатывается. Если в очереди более поздний выбор той же
    // группы, перерисует он.
    private suspend fun MainDesign.applySelection(slot: Slot) {
        val name = pendingSelections.remove(slot) ?: return
        val group = slot.group

        var failure: Exception? = null

        val sending = Sending(slot, name)

        sendingSelection = sending

        // Выбор — подписке, на чьём списке его сделали; в ядро ли он уйдёт или
        // будет ждать её загрузки, решает ядро по тому, что держит
        val applied = try {
            when {
                serversReadOnly -> true
                else -> {
                    val done = withClash { select(slot.owner, group, name).also { sending.delivered = true } }

                    if (done && offlineGroups.isNotEmpty() && slot.owner == groupsShownFor) {
                        if (name.isEmpty()) {
                            offlineSelections.remove(group)
                        } else {
                            offlineSelections[group] = name
                        }
                    }

                    done
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e

            false
        }

        sendingSelection = null

        if (slot !in pendingSelections) {
            proxyGroupNames.indexOf(group).takeIf { it >= 0 }?.let { reloadProxyGroup(it) }

            if (!applied && failure == null) {
                showToast(DesignR.string.clod_select_failed, ToastDuration.Long)
            }
        }

        failure?.let { throw it }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTraffic(queryTrafficTotal())
        }
    }

    private fun shouldAskNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return false

        return notificationPromptDue(
            granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            requested = uiStore.notificationsRequested,
            snoozedAt = uiStore.notificationsSnoozedAt,
            snoozes = uiStore.notificationsSnoozes,
            now = System.currentTimeMillis(),
        )
    }

    private fun isBatteryIgnored(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false

        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private suspend fun alwaysOnState(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return null

        return when (ServiceSettings.access { vpnAlwaysOn }) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    // prompt — показать или убрать вопрос; null — оставить как есть
    private suspend fun MainDesign.fetchReliability(prompt: Boolean? = null) {
        setReliability(batteryIgnored = isBatteryIgnored(), alwaysOn = alwaysOnState(), prompt = prompt)
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
                resId = DesignR.string.clod_sub_not_selected,
                duration = ToastDuration.Long,
                actionLabel = DesignR.string.clod_tab_subscriptions,
                onAction = { launch { selectTab(MainTab.Subscriptions) } },
            )

            return
        }

        setConnecting()

        watchStart()

        try {
            // Служба при старте читает настройки сама: записи с экранов настроек
            // должны лечь раньше.
            ServiceSettings.access { }

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
                    sizeBytes = state.available.platform.size,
                    notes = state.available.manifest.notes,
                ),
            )
            is UpdateTask.State.Downloading -> setUpdate(
                UpdateState(
                    version = state.available.manifest.version,
                    sizeBytes = state.available.platform.size,
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
                        detail = state.kind?.let { getString(it.text) } ?: state.detail?.let(Redact::text),
                        kind = NoticeKind.Error,
                    )
                }

                UpdateTask.dismiss()
            }
            is UpdateTask.State.Failed -> {
                showToast(
                    DesignR.string.clod_update_failed_generic,
                    ToastDuration.Long,
                    detail = state.kind?.let { getString(it.text) } ?: state.detail?.let(Redact::text),
                    kind = NoticeKind.Error,
                )

                UpdateTask.dismiss()
            }
            UpdateTask.State.Idle, is UpdateTask.State.Checking, is UpdateTask.State.Ready ->
                setUpdate(null)
        }
    }

    private fun openExternalUrl(url: String) {
        val uri = Uri.parse(url)

        if (uri.scheme?.lowercase() !in listOf("https", "tg", "mailto")) {
            launch { design?.showToast(DesignR.string.invalid_url, ToastDuration.Long) }

            return
        }

        try {
            if (!startExternal(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                launch { design?.showNoAppForLink(url) }
            }
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

    private suspend fun MainDesign.loadCoreVersion() {
        setCoreVersion(
            withContext(Dispatchers.IO) {
                Bridge.nativeCoreVersion()
                    .substringBefore('_')
                    .removePrefix("v")
            },
        )
    }

    private suspend fun MainDesign.loadRoutingData() {
        setRoutingDataUpdating(RoutingDataUpdate.state.value is RoutingDataUpdate.State.Running)

        setRoutingData(
            files = GeoData.query(this@MainActivity),
            providers = RoutingDataUpdate.updatableProviders().map {
                ProviderFileState(
                    key = RoutingDataUpdate.keyOf(it),
                    name = it.name,
                    updatedAt = it.updatedAt,
                    updating = RoutingDataUpdate.isBusy(it),
                )
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
                kind = NoticeKind.Error,
            )
            geo.failed.isNotEmpty() -> showToast(
                DesignR.string.clod_geo_update_partial,
                ToastDuration.Long,
                detail = listOfNotNull(geo.failed.joinToString(", ").ifEmpty { null }, reconnect)
                    .joinToString(" · ").ifEmpty { null },
            )
            outcome.providersFailed.isNotEmpty() -> showToast(
                DesignR.string.clod_geo_providers_failed,
                ToastDuration.Long,
                detail = listOfNotNull(outcome.providersFailed.joinToString(", "), reconnect)
                    .joinToString(" · "),
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
        data class Outcome(val geo: GeoData.UpdateResult, val providersFailed: List<String>)

        data class ProviderEvent(
            val key: String,
            val updating: Boolean,
            val error: String? = null,
            val updatedAt: Long? = null,
        )

        fun keyOf(provider: Provider): String = "${provider.type}/${provider.name}"

        fun isBusy(provider: Provider): Boolean = keyOf(provider) in busy

        sealed interface State {
            data object Idle : State
            data object Running : State
            data class Done(val outcome: Outcome) : State
        }

        private val current = MutableStateFlow<State>(State.Idle)

        val state: StateFlow<State> = current

        private val events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 64)

        val providerEvents: SharedFlow<ProviderEvent> = events

        private val busy: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

        private suspend fun updateOne(provider: Provider): String? {
            val key = keyOf(provider)

            events.emit(ProviderEvent(key, updating = true))

            return try {
                withClash { updateProvider(provider.type, provider.name) }

                busy.remove(key)

                events.emit(ProviderEvent(key, updating = false, updatedAt = System.currentTimeMillis()))

                null
            } catch (e: CancellationException) {
                busy.remove(key)

                events.emit(ProviderEvent(key, updating = false))

                throw e
            } catch (e: Exception) {
                Log.w("Update provider ${provider.name}: $e", e)

                val reason = Redact.text(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)

                busy.remove(key)

                events.emit(ProviderEvent(key, updating = false, error = reason))

                reason
            }
        }

        fun startProvider(key: String) {
            if (current.value is State.Running || !busy.add(key)) return

            Global.launch {
                try {
                    val provider = updatableProviders().firstOrNull { keyOf(it) == key } ?: return@launch

                    updateOne(provider)
                } finally {
                    busy.remove(key)
                }
            }
        }

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

                    if (geo.updated.isNotEmpty()) {
                        try {
                            withClash { reloadGeoData() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("Reload geo data in core: $e", e)
                        }
                    }

                    val providersFailed = updatableProviders().mapNotNull {
                        val key = keyOf(it)

                        if (!busy.add(key)) return@mapNotNull null

                        try {
                            updateOne(it)?.let { _ -> it.name }
                        } finally {
                            busy.remove(key)
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
        val store = AppStore(this)
        val about = AboutState(
            autoCheckUpdate = store.autoCheckUpdate,
            prerelease = store.prereleaseChannel,
        )

        val bundle = restored ?: return MainScreenState(about = about)

        return MainScreenState(
            selectedTab = bundle.getString(KEY_TAB)?.let { MainTab.valueOf(it) } ?: MainTab.Home,
            subScreen = bundle.getString(KEY_SUB_SCREEN)?.let { SubScreen.valueOf(it) },
            subscriptions = SubscriptionsState(selectedGroup = bundle.getString(KEY_SUB_GROUP)),
            about = about,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        val design = design ?: return

        outState.putString(KEY_TAB, design.selectedTab.name)
        outState.putString(KEY_SUB_SCREEN, design.subScreen?.name)
        outState.putString(KEY_GROUP, design.selectedGroupName)
        outState.putString(KEY_SUB_GROUP, design.selectedSubscriptionGroup)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        refreshDynamicShortcuts(uiStore.hideAppIcon)
    }
}
