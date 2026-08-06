package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
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
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.service.model.PanelGroup
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.GeoData
import com.github.kr328.clash.util.patchSubscriptionGroup
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        // Один раз за жизнь экрана: версии не меняются, а номер нужен уже на
        // вкладке «Ещё» — он стоит подписью к пункту «О приложении».
        design.loadAbout()

        // Обновление приложения из GitHub Releases. Ядро отдельно не обновляется:
        // оно вкомпилировано в APK, и подменить его по одному файлу нельзя.
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

                            // Возврат с системного экрана разрешений: если его
                            // выдали, продолжаем то, ради чего туда уходили.
                            // Иначе обновление молча теряется, а следующая
                            // проверка — только через сутки.
                            if (awaitingInstallPermission &&
                                ApkInstaller.canInstall(this@MainActivity)
                            ) {
                                awaitingInstallPermission = false

                                launch { design.startUpdate() }
                            }
                        }
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
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
                                val patched = withClash { patchSelector(group, request.name) }

                                if (patched) {
                                    design.reloadProxyGroup(request.index)
                                } else {
                                    // Ядро отказалось: узел мог исчезнуть после
                                    // обновления подписки. Молчать нельзя — нажатие
                                    // выглядело бы как несработавшее.
                                    design.showToast(
                                        DesignR.string.clod_select_failed,
                                        ToastDuration.Long,
                                    )
                                }
                            }
                        }
                        is MainDesign.Request.UrlTest -> launch { design.runHealthCheck() }
                        is MainDesign.Request.PatchMode -> {
                            withClash {
                                val override = queryOverride(Clash.OverrideSlot.Session)

                                override.mode = request.mode

                                patchOverride(Clash.OverrideSlot.Session, override)
                            }

                            design.fetch()
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
                            // Отдельная корутина: обновление всех подписок ходит
                            // в сеть по очереди, а цикл событий должен жить.
                            launch {
                                design.setProfilesUpdating(true)

                                try {
                                    withProfile {
                                        queryAll().forEach { profile ->
                                            if (profile.imported &&
                                                profile.type != Profile.Type.File
                                            ) {
                                                update(profile.uuid)
                                            }
                                        }
                                    }
                                } finally {
                                    design.setProfilesUpdating(false)
                                }
                            }
                        }
                        is MainDesign.Request.ActivateProfile -> {
                            val profile = request.profile

                            if (profile.imported) {
                                withProfile { setActive(profile) }
                            } else {
                                // Профиль ещё не сохранён: активировать нечего,
                                // ведём в редактор, как это делал старый экран.
                                design.showToast(
                                    DesignR.string.active_unsaved_tips,
                                    ToastDuration.Long,
                                ) {
                                    setAction(DesignR.string.edit) {
                                        startActivity(
                                            PropertiesActivity::class.intent
                                                .setUUID(profile.uuid),
                                        )
                                    }
                                }
                            }
                        }
                        is MainDesign.Request.UpdateProfile ->
                            withProfile { update(request.profile.uuid) }
                        is MainDesign.Request.EditProfile ->
                            startActivity(
                                PropertiesActivity::class.intent.setUUID(request.profile.uuid),
                            )
                        is MainDesign.Request.DeleteProfile ->
                            withProfile { delete(request.profile.uuid) }
                        is MainDesign.Request.SetSubscriptionGroup -> {
                            patchSubscriptionGroup(request.profile.uuid, request.group)

                            design.fetch()
                        }
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
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
                        MainDesign.Request.OpenOverrideSettings ->
                            startActivity(OverrideSettingsActivity::class.intent)
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
                            design.setRoutingData(GeoData.query(this@MainActivity))

                        MainDesign.Request.UpdateRoutingData ->
                            launch { design.updateRoutingData() }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                        design.fetchSession()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        sessionStartedAt = if (clashRunning) {
            withContext(Dispatchers.IO) { ServiceStore(this@MainActivity).clashStartedAt }
        } else {
            0
        }

        fetchSession()

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        val profiles = withProfile { queryAll() }
        val groups = querySubscriptionGroups()
        val items = profiles.map {
            SubscriptionItem(it, queryPanelInfo(it.uuid), groups[it.uuid])
        }

        setProfiles(items)
        setActiveProfile(items.firstOrNull { it.profile.active })

        reloadProxyGroups()
    }

    /**
     * Имена групп в том же порядке, в каком они лежат в состоянии экрана: запросы
     * от экрана приходят с индексом, а ядру нужно имя.
     */
    private var proxyGroupNames: List<String> = emptyList()

    /**
     * Состав групп из файла подписки. Пока туннель не поднят, спрашивать ядро
     * бесполезно, и переключение чипов обслуживается отсюда.
     */
    private var offlineGroups: List<PanelGroup> = emptyList()

    /** Набор групп, для которого задержки уже мерили в этой сессии. */
    private var healthCheckedGroups: List<String> = emptyList()

    private suspend fun MainDesign.reloadProxyGroups() {
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }

        if (names.isEmpty()) {
            // Туннель не поднят — ядро о группах ничего не знает. Берём состав
            // из файла подписки: список серверов человек хочет видеть и до
            // подключения, хотя бы без задержек.
            loadOfflineProxyGroups()

            return
        }

        proxyGroupNames = names
        offlineGroups = emptyList()

        setProxyGroupNames(names)

        reloadProxyGroup(selectedGroup)

        // Задержки меряем сами, как только появился рабочий список: человек
        // подключился или добавил подписку — и сразу видит, куда быстрее.
        // Повторно на каждое событие не гоняем: набор групп не изменился.
        if (names != healthCheckedGroups) {
            healthCheckedGroups = names

            launch { runHealthCheck() }
        }
    }

    /**
     * Проверка задержек.
     *
     * Проверяются ВСЕ группы, а не только открытая. У группы типа `select`
     * в конфигах панели обычно нет своего `url`, и её проверка здоровья
     * не делает ничего — а у соседней `url-test` он есть. Узлы в ядре общие
     * (`tunnel.Proxies()` держит по одному объекту на имя), поэтому проверка
     * любой группы обновляет задержки и во всех остальных.
     */
    private suspend fun MainDesign.runHealthCheck() {
        if (proxyGroupNames.isEmpty() || offlineGroups.isNotEmpty()) return

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

    private suspend fun MainDesign.loadOfflineProxyGroups() {
        val active = withProfile { queryActive() }
        val panel = active?.let { queryPanelInfo(it.uuid) }
        offlineGroups = panel?.groups.orEmpty()
        proxyGroupNames = offlineGroups.map { it.name }
        // Отключились — при следующем подключении меряем заново: старые
        // задержки к новой сети отношения не имеют.
        healthCheckedGroups = emptyList()

        setProxyGroupNames(proxyGroupNames, offline = true)

        fillOfflineProxyGroup(selectedGroup)
    }

    private suspend fun MainDesign.fillOfflineProxyGroup(index: Int) {
        val group = offlineGroups.getOrNull(index) ?: return

        setProxyGroup(
            index = index,
            now = "",
            selectable = false,
            proxies = group.proxies.map { name ->
                // Задержки нет и быть не может: измеряет её только ядро,
                // а оно ещё не запущено. Ноль экран показывает прочерком.
                Proxy(
                    name = name,
                    title = name,
                    subtitle = "",
                    type = "",
                    delay = 0,
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
        /**
         * Группы, в которых узел можно закрепить руками.
         *
         * Это ровно те типы, что реализуют `SelectAble` в mihomo: `Selector`,
         * `URLTest` и `Fallback` (`adapter/outboundgroup/util.go`). Раньше здесь
         * стоял только `Selector` — и в подписках Remnawave, где основная группа
         * почти всегда `url-test`, выбор сервера не работал вообще.
         *
         * NB: у `url-test` закрепление не вечное — ядро уводит с закреплённого
         * узла, если тот перестал отвечать, и это правильное поведение.
         */
        private val SELECTABLE_GROUPS = setOf("Selector", "URLTest", "Fallback")

        /**
         * Порт локального прокси ядра. Через него апдейтер повторяет запрос,
         * если GitHub недоступен напрямую — ровно как на десктопе.
         * Значение задаётся в `native/config/process.go`.
         */
        private const val LOCAL_PROXY_PORT = 7890
    }

    /**
     * Момент подъёма туннеля по данным службы. Читается один раз на подключение:
     * значение живёт в общих настройках, а это межпроцессный вызов — дёргать его
     * каждую секунду ради тикающего таймера незачем.
     */
    private var sessionStartedAt: Long = 0

    private suspend fun MainDesign.fetchSession() {
        val startedAt = sessionStartedAt

        setSessionSeconds(
            if (startedAt > 0) {
                ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
            } else {
                0
            },
        )
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTraffic(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    launch { selectTab(MainTab.Subscriptions) }
                }
            }

            return
        }

        // Ставим «Подключение…» до похода в службу: поднятие туннеля занимает
        // заметное время, и без этого первое нажатие выглядит как непрошедшее.
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
                    // Пользователь отказал в разрешении на VPN. События от службы
                    // не будет, поэтому «Подключение…» надо снять руками — иначе
                    // экран так и останется в промежуточном состоянии.
                    setClashRunning(clashRunning)
                }
            }
        } catch (e: Exception) {
            setClashRunning(clashRunning)
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    /** Обновление, о котором уже сказали человеку: нужно, чтобы продолжить после разрешения. */
    private var pendingUpdate: Updater.Available? = null

    /** Ушли на системный экран за разрешением на установку и ждём возврата. */
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
            // Разрешение выдаётся на системном экране, оттуда мы вернёмся
            // событием ActivityStart и продолжим сами.
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

                    // Перегрузка с CharSequence, а не с Exception: fold и
                    // onFailure отдают Throwable, а он шире.
                    showExceptionToast(it.message ?: it.toString())
                }
            },
            onFailure = {
                Log.w("Download update: $it", it)

                showExceptionToast(it.message ?: it.toString())
            },
        )
    }

    /**
     * Ссылки панели (продлить, докупить, объявление) открываются браузером.
     * Своего окна для них нет и не нужно: это страницы оплаты чужого сервиса.
     */
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

    private suspend fun MainDesign.loadAbout() {
        val store = AppStore(this@MainActivity)

        val versionName = withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }

        setAbout(
            versionName = versionName,
            // Подчёркивания в версии ядра — из имени тега сборки; в тексте
            // на экране они читаются как опечатка.
            coreVersion = Bridge.nativeCoreVersion().replace("_", "-"),
            autoCheckUpdate = store.autoCheckUpdate,
            prerelease = store.nightlyChannel,
        )
    }

    /**
     * Обновление списков маршрутизации. Ядро перечитывает их при следующем
     * подъёме туннеля — на лету подменять файлы под работающим ядром нельзя.
     */
    private suspend fun MainDesign.updateRoutingData() {
        setRoutingDataUpdating(true)

        try {
            GeoData.update(this@MainActivity, LOCAL_PROXY_PORT).fold(
                onSuccess = {
                    showToast(DesignR.string.clod_geo_updated, ToastDuration.Short)
                },
                onFailure = {
                    Log.w("Update geo data: $it", it)

                    showToast(DesignR.string.clod_geo_update_failed, ToastDuration.Long)
                },
            )

            setRoutingData(GeoData.query(this@MainActivity))
        } finally {
            setRoutingDataUpdating(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
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
