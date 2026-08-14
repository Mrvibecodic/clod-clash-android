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
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.model.PanelGroup
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.design.MainDesign
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

        // Подписок нет вовсе — открываемся сразу на их вкладке.
        //
        // Показывать на главной в этом состоянии нечего: кнопка подключения
        // ничего не подключит, карточки подписки нет, список серверов пуст.
        // Единственное осмысленное действие — добавить подписку, и оно живёт
        // на соседней вкладке, куда человек с пустой главной должен догадаться
        // перейти сам.
        //
        // Условие — именно «ни одной подписки», а не «нет активной» и не
        // «подписка истекла»: в этих случаях на главной есть что показать
        // (карточка со сроком и кнопкой продления), и увести с неё значило бы
        // спрятать причину происходящего.
        if (!design.hasProfiles) {
            design.selectTab(MainTab.Subscriptions)
        }

        // Экран мог быть закрыт, пока обновление заканчивалось: наблюдателя
        // не было, ответ не дошёл. Чистим протухшее до первой подписки, иначе
        // свежий экран успел бы моргнуть крутящимся значком.
        ProfileUpdates.prune()

        launch {
            ProfileUpdates.running.collect { design.setUpdatingProfiles(it.keys) }
        }

        // Один раз за жизнь экрана: версия не меняется, а номер нужен уже
        // на вкладке «Ещё» — он стоит подписью к пункту «О приложении».
        design.loadVersionName()

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
                        Event.ClashStop -> {
                            // Задержки, померенные до и во время подключения,
                            // к новой сети отношения не имеют: телефон мог
                            // уехать с Wi-Fi на LTE ровно тем же движением,
                            // которым туннель и выключили.
                            offlineDelays = emptyMap()

                            design.fetch()

                            // Напоминания о сроке и трафике подписки.
                            // Обновления расписаны будильником, но интервал
                            // можно поставить в «вручную» — тогда будильника
                            // нет вовсе, а подписка кончается всё равно.
                            // Открытие экрана — второй и последний повод
                            // проверить пороги.
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
                                    // Досюда доходить нечему: экран глушит
                                    // такое нажатие тостом. Но если дойдёт —
                                    // трогать живое ядро именами из файла
                                    // точно не нужно.
                                    return@let
                                }

                                if (offlineGroups.isNotEmpty()) {
                                    // Туннель не поднят: ядру выбор отдать некуда,
                                    // запоминаем в базе. Он применится сам сразу
                                    // после загрузки профиля.
                                    withClash { rememberSelection(group, request.name) }

                                    offlineSelections[group] = request.name

                                    design.fillOfflineProxyGroup(request.index)

                                    return@let
                                }

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
                            // Замок проверяется ЗДЕСЬ, а не только в интерфейсе:
                            // это единственная воронка, через которую режим
                            // вообще меняется, и спрятанная строка сама по себе
                            // замком не является.
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
                            // Отдельная корутина: перебор подписок ходит в
                            // служебный процесс, а цикл событий должен жить.
                            launch {
                                var targets = emptyList<UUID>()

                                try {
                                    // Сначала список, потом отметка, и только
                                    // потом запросы. Отмечать внутри
                                    // `withProfile` нельзя: при смерти
                                    // служебного процесса он повторяет блок
                                    // целиком, и уже завершившиеся подписки
                                    // получили бы отметку повторно — снимать
                                    // её было бы уже нечем.
                                    targets = withProfile {
                                        queryAll()
                                            .filter { it.imported && it.type != Profile.Type.File }
                                            .map { it.uuid }
                                    }

                                    if (targets.isEmpty()) {
                                        // Обновлять нечего: на главном экране
                                        // кнопка есть всегда, в том числе когда
                                        // подписок нет вовсе, и молчание в ответ
                                        // на нажатие читалось бы как поломка.
                                        design.showToast(
                                            DesignR.string.clod_sub_nothing_to_update,
                                            ToastDuration.Short,
                                        )

                                        return@launch
                                    }

                                    ProfileUpdates.start(targets)

                                    withProfile { targets.forEach { update(it) } }
                                } catch (e: CancellationException) {
                                    // Экран уничтожают (например, поворотом), а
                                    // обновление в служебном процессе живёт
                                    // дальше. Отметку снимать нельзя — её
                                    // должен снять ответ от процесса.
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
                        is MainDesign.Request.UpdateProfile -> {
                            // Отдельная корутина: запрос идёт в служебный
                            // процесс, а цикл событий должен оставаться живым,
                            // иначе экран не перерисуется и значок не поедет.
                            launch {
                                val uuid = request.profile.uuid

                                ProfileUpdates.start(listOf(uuid))

                                try {
                                    withProfile { update(uuid) }
                                } catch (e: CancellationException) {
                                    // Отмена корутины — это уничтожение экрана,
                                    // а не отказ обновления: отметку оставляем
                                    // жить, её снимет ответ служебного процесса.
                                    throw e
                                } catch (e: Exception) {
                                    // Иначе карточка крутилась бы до истечения
                                    // срока отметки, хотя обновление даже не
                                    // началось.
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
                // Секундный опрос — только пока экран виден. `fetchTraffic`
                // и `fetchSession` уходят через Binder в процесс службы, то есть
                // это два межпроцессных вызова в секунду; у свёрнутого приложения
                // их результат некому показать, а телефон за них платит.
                // Ветка `select` пересобирается на каждом обороте цикла,
                // а `Event.ActivityStart` / `Event.ActivityStop` этот цикл будят —
                // значит опрос возобновляется ровно тогда, когда экран вернулся.
                if (clashRunning && activityStarted) {
                    ticker.onReceive {
                        design.fetchTraffic()
                        design.fetchSession()

                        // Сообщение о завершении могло не дойти: пока экран был
                        // остановлен, наблюдателя не было. Здесь отметки с
                        // истёкшим сроком снимаются.
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
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

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

    /**
     * Задержки, измеренные до подключения: имя узла -> мс.
     *
     * Ядро в этот момент профиля не знает, поэтому держать их негде, кроме
     * как здесь. Сбрасываются при смене подписки: узлы у разных подписок
     * называются одинаково, а цифры от предыдущей — вранье.
     */
    private var offlineDelays: Map<String, Int> = emptyMap()

    /** Профиль, к которому относятся [offlineDelays] и [offlineSelections]. */
    private var offlineProfile: UUID? = null

    /** Сохранённый выбор узла по группам — показываем его и до подключения. */
    private val offlineSelections: MutableMap<String, String> = mutableMapOf()

    /** Подписка, к чьему набору избранного относятся отметки на экране. */
    private var favoritesProfile: UUID? = null

    /**
     * Список показан из файла, но ядро работает: режим «Прямое соединение».
     * Ни мерить своим разбором, ни запоминать выбор в таком состоянии нельзя.
     */
    private var serversReadOnly: Boolean = false

    private suspend fun MainDesign.reloadProxyGroups() {
        // Только группы, в которых узел можно выбрать руками. Балансировщик
        // (`load-balance`) в списке групп не нужен: открыть его можно,
        // а выбрать внутри нечего. Настройкой это не делаем — показывать
        // человеку тумблер «показывать группы, в которых ничего нельзя
        // сделать» не за чем.
        val names = if (clashRunning) withClash { queryProxyGroupNames(true) } else emptyList()

        if (names.isEmpty()) {
            // Список берём из файла подписки: видеть свои серверы человек
            // хочет и до подключения.
            //
            // Отдельный случай — режим «Прямое соединение»: ядро работает,
            // но групп не отдаёт намеренно. Там ни мерить своим разбором
            // конфига (он лезет в те же глобальные настройки ядра), ни
            // запоминать выбор «на потом» нельзя — человек уже подключён.
            //
            // Проверяем именно режим, а не «ядро работает»: между стартом
            // службы и загрузкой профиля групп тоже нет, и без этой проверки
            // экран на пару секунд каждого подключения врал бы про Direct.
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

    /**
     * Проверка задержек до подключения.
     *
     * Ядро о профиле ещё не знает, поэтому меряет не оно: `testProfileDelays`
     * разбирает файл подписки, создаёт узлы, опрашивает их и выбрасывает,
     * не трогая состояние ядра. Проба — обычный сокет до сервера узла, туннель
     * для неё не нужен, и цифры получаются те же, что и после подключения.
     */
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
        // Отключились — при следующем подключении меряем заново: старые
        // задержки к новой сети отношения не имеют.
        healthCheckedGroups = emptyList()

        if (active?.uuid != offlineProfile) {
            // Сменилась подписка: узлы у разных подписок называются одинаково,
            // и старые цифры относились бы не к тем серверам.
            offlineProfile = active?.uuid
            offlineDelays = emptyMap()
            offlineSelections.clear()
        }

        // Что выбрано — знает база: выборы там переживают и перезапуск, и то,
        // что ядро о них ещё не слышало. Пропавшие записи убираем, иначе
        // на экране осталась бы галочка на узле, которого уже нет.
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
            // Выбирать можно и до подключения — но только там, где выбор
            // потом примут: в `load-balance` и `relay` ядро откажет,
            // и запись из базы молча пропала бы, а человеку уже пообещали.
            selectable = !readOnly && group.type in OFFLINE_SELECTABLE_GROUPS,
            proxies = group.proxies.map { name ->
                Proxy(
                    name = name,
                    title = name,
                    subtitle = "",
                    type = "",
                    // Ноль означает «ещё не мерили»; экран рисует его прочерком.
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
         * То же самое, но как это записано в самом файле подписки: до подключения
         * тип группы известен только оттуда (`panel.json` пишет его как есть).
         */
        private val OFFLINE_SELECTABLE_GROUPS = setOf("select", "url-test", "fallback")

        /** Ответ `testProfileDelays`: имя узла -> задержка в мс. */
        private val DELAYS_SERIALIZER = MapSerializer(String.serializer(), Int.serializer())

        /**
         * Порт локального прокси ядра. Через него апдейтер повторяет запрос,
         * если GitHub недоступен напрямую — ровно как на десктопе.
         * Значение задаётся в `native/config/process.go`.
         */
        private const val LOCAL_PROXY_PORT = 7890
    }

    /**
     * Момент подъёма туннеля по данным службы, в двух парах часов. Читается один
     * раз на подключение: значение живёт в общих настройках, а это межпроцессный
     * вызов — дёргать его каждую секунду ради тикающего таймера незачем.
     */
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

    /**
     * Только номер версии приложения — он стоит подписью к пункту
     * «О приложении» во вкладке «Ещё», и нужен сразу.
     *
     * Версию ядра здесь не спрашиваем намеренно. Первое же обращение
     * к [Bridge] выполняет его инициализатор: подгружает нативную библиотеку
     * и вызывает `nativeInit`, то есть поднимает ядро в текущем процессе.
     * Ядро живёт в отдельном (`:background`), и заводить второе в UI-процессе
     * ради подписи к одной строке — тем более на старте — нельзя.
     */
    private suspend fun MainDesign.loadVersionName() {
        setAppVersion(
            withContext(Dispatchers.IO) {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            },
        )
    }

    /** Полные данные экрана «О приложении». Только по открытию экрана. */
    private suspend fun MainDesign.loadAbout() {
        withContext(Dispatchers.IO) {
            val store = AppStore(this@MainActivity)

            setAbout(
                versionName = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                // Подчёркивания в версии ядра — из имени тега сборки; в тексте
                // на экране они читаются как опечатка.
                coreVersion = Bridge.nativeCoreVersion().replace("_", "-"),
                autoCheckUpdate = store.autoCheckUpdate,
                prerelease = store.nightlyChannel,
            )
        }
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
