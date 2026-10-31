package com.github.kr328.clash

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isLightNavigationBarCompat
import com.github.kr328.clash.common.compat.isLightStatusBarsCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.AppLocale
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.ui.DayNight
import com.github.kr328.clash.design.util.resolveThemedBoolean
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.humanText
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.service.R as ServiceR
import com.github.kr328.clash.service.util.profileDisplayName
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.service.store.ServiceSettings
import com.github.kr328.clash.service.util.UpdateFailures
import com.github.kr328.clash.service.util.humanizeUpdateFailure
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.ActivityResultLifecycle
import com.github.kr328.clash.util.showNoAppForLink
import com.github.kr328.clash.util.startExternal
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.ServiceUnavailableException
import com.github.kr328.clash.util.appCompatLocalesLoaded
import com.github.kr328.clash.util.applyHideFromRecents
import com.github.kr328.clash.util.serviceUnavailableHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.github.kr328.clash.design.R

// defer бросает его, когда уйти с экрана нельзя: закрытие молча потеряло бы
// сделанное на нём (например, правки не записались).
class FinishCancelled : Exception()

abstract class BaseActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope by (MainScope() + serviceUnavailableHandler),
    Broadcasts.Observer {

    protected val uiStore by lazy { UiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)

    @Volatile
    protected var startupStage: String? = null
    protected var activityStarted: Boolean = false
    protected val clashRunning: Boolean
        get() = Remote.broadcasts.clashRunning

    protected val clashActive: Boolean
        get() = Remote.broadcasts.clashActive
    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) setContentView(value.root)
        }

    private var defer: suspend () -> Unit = {}
    private var deferRunning = false
    private var dayNight: DayNight = DayNight.Day

    protected abstract suspend fun main()

    fun defer(operation: suspend () -> Unit) {
        this.defer = operation
    }

    suspend fun <I, O> startActivityForResult(
        contracts: ActivityResultContract<I, O>,
        input: I,
    ): O = withContext(Dispatchers.Main) {
        // Ключ свой у каждого запроса: реестр с занятыми ключами переживает
        // пересоздание экрана, и повторённый ключ получил бы ответ чужого окна.
        val requestKey = UUID.randomUUID().toString()
        // Отдельная ссылка: после проверки `!is GetContent` тип contracts сужается
        // смарт-кастом, и parseResult перестаёт возвращать O
        val contract: ActivityResultContract<I, O> = contracts

        try {
            ActivityResultLifecycle().use { lifecycle, start ->
                suspendCancellableCoroutine { c ->
                    activityResultRegistry.register(requestKey, lifecycle, contracts) {
                        c.resume(it)
                    }.apply { start() }.launch(input)
                }
            }
        } catch (e: ActivityNotFoundException) {
            if (contracts !is GetContent && contracts !is CreateDocument) throw e

            design?.showToast(R.string.clod_no_file_picker, ToastDuration.Long, kind = NoticeKind.Error)

            contract.parseResult(Activity.RESULT_CANCELED, null)
        }
    }

    // Отказ службы приходит через binder любым из разрешённых Parcel типов
    // (IllegalArgument, IllegalState, Security, NullPointer…): экран показывает
    // причину и живёт дальше, а не падает — обработчик области активности
    // пропускает только ServiceUnavailableException.
    protected suspend fun reportFailures(where: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Отказ службы — ожидаемый, без стека
            Log.w("$where: $e", e.takeUnless { it is ServiceUnavailableException })

            design?.showExceptionToast(e)
        }
    }

    // Разрешение на уведомления (Android 13+); false — просить не о чем.
    protected suspend fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return false

        try {
            startActivityForResult(RequestPermission(), android.Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Request notifications: $e", e)
        }

        return true
    }

    // Ссылка во внешнее приложение; нет подходящего — говорим, какая
    protected fun openExternalUrl(url: String) {
        try {
            if (!startExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                launch { design?.showNoAppForLink(url) }
            }
        } catch (e: Exception) {
            launch { design?.showExceptionToast(e) }
        }
    }

    suspend fun setContentDesign(design: D) {
        suspendCoroutine<Unit> {
            window.decorView.post {
                this.design = design
                it.resume(Unit)
            }
        }
    }

    protected var restored: Bundle? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState
        applyDayNight()
        syncAppLocale()

        // Флаг хранится в задаче у системы и переживает и поворот, и смерть процесса:
        // ставится новому экрану, а при смене настройки — явно (AppSettingsActivity).
        if (savedInstanceState == null) {
            applyHideFromRecents(uiStore.hideFromRecents)
        }

        launch {
            try {
                main()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeOnFailure(e)
            }
        }
    }

    // Экран, чей main() кончился отказом, без своего цикла событий мёртв: он
    // закрывается с сообщением, а не роняет приложение и не остаётся пустым.
    // Отказ службы на уже показанном экране — как раньше, только сообщением.
    private fun closeOnFailure(e: Exception) {
        if (e is ServiceUnavailableException && design != null) throw e

        Log.w("Screen ${javaClass.simpleName} failed: $e", e.takeUnless { it is ServiceUnavailableException })

        Toast.makeText(this, humanText(e) ?: getString(R.string.clod_action_failed), Toast.LENGTH_LONG).show()

        deferRunning = true

        super.finish()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        Remote.broadcasts.addObserver(this)
        events.trySend(Event.ActivityStart)
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        Remote.broadcasts.removeObserver(this)
        events.trySend(Event.ActivityStop)
    }

    override fun onDestroy() {
        design?.cancel()
        cancel()
        super.onDestroy()
    }

    // Приложение закрывает все экраны разом (служба упала, APK повреждён): экран
    // закрывается сразу, а defer (запись правок) доделывается уже без него.
    fun finishAnyway() {
        finish()

        super.finish()
    }

    override fun finish() {
        if (deferRunning) return
        deferRunning = true

        launch {
            var close = true

            try {
                // Запись правок не прерывается закрытием экрана: она дождётся
                // службы, даже если экран уже уничтожен.
                withContext(NonCancellable) { defer() }
            } catch (e: FinishCancelled) {
                close = false
                deferRunning = false
            } finally {
                if (close) {
                    withContext(NonCancellable) {
                        super.finish()
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (queryDayNight(newConfig) != dayNight) {
            ApplicationObserver.createdActivities.forEach {
                it.recreate()
            }
        }
    }

    override fun onProfileChanged(uuid: UUID?) {
        events.trySend(Event.ProfileChanged(uuid))
    }

    override fun onProfileUpdateStarted(uuid: UUID?) = Unit

    override fun onProfileUpdateCompleted(uuid: UUID?, warning: String?) {
        if (warning == null || !activityStarted) return

        launch {
            design?.showToast(message = warning, duration = ToastDuration.Long)
        }
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if (reason == null || !activityStarted) return

        val human = humanizeUpdateFailure(reason)

        launch {
            // Какая подписка — как в уведомлении: при «Обновить все» иначе не понять
            val name = uuid?.let { id ->
                try {
                    withProfile(retry = false) { queryAll().firstOrNull { it.uuid == id } }?.let {
                        profileDisplayName(queryPanelInfo(id), it.name, it.nameManual)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            val cause = human ?: getString(ServiceR.string.update_failure)

            design?.showToast(
                message = name?.let { getString(ServiceR.string.format_update_failure, it, cause) } ?: cause,
                duration = ToastDuration.Long,
                detail = UpdateFailures.detail(reason) ?: Redact.text(reason),
                kind = NoticeKind.Error,
            )
        }
    }

    override fun onProfileLoaded() {
        events.trySend(Event.ProfileLoaded)
    }

    override fun onFreezeMarksChanged(uuid: UUID?) {
        events.trySend(Event.FreezeMarksChanged(uuid))
    }

    override fun onHiddenServersChanged() {
        events.trySend(Event.HiddenServersChanged)
    }

    override fun onTunnelAppsChanged() {
        events.trySend(Event.TunnelAppsChanged)
    }

    override fun onProfileLoadFailed(uuid: UUID?, reason: String?) {
        // Подписка не названа: после сбоя загрузки экран перечитывается целиком.
        events.trySend(Event.ProfileChanged(null))

        if (reason != null && activityStarted) {
            launch {
                design?.showExceptionToast(reason)
            }
        }
    }

    override fun onStarting(stage: String?) {
        startupStage = stage

        events.trySend(Event.ClashStarting)
    }

    override fun onServiceRecreated() {
        events.trySend(Event.ServiceRecreated)
    }

    override fun onStarted() {
        events.trySend(Event.ClashStart)
    }

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            NotificationManagerCompat.from(this).cancel(ServiceR.id.nf_clash_start_failed)

            launch {
                design?.showExceptionToast(cause)
            }
        }
    }

    private fun queryDayNight(config: Configuration = resources.configuration): DayNight {
        return when (uiStore.darkMode) {
            DarkMode.Auto -> if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DayNight.Night else DayNight.Day
            DarkMode.ForceLight -> DayNight.Day
            DarkMode.ForceDark -> DayNight.Night
        }
    }

    private fun applyDayNight(config: Configuration = resources.configuration) {
        val dayNight = queryDayNight(config)
        when (dayNight) {
            DayNight.Night -> theme.applyStyle(R.style.AppThemeDark, true)
            DayNight.Day -> theme.applyStyle(R.style.AppThemeLight, true)
        }

        window.isAllowForceDarkCompat = false
        window.isSystemBarsTranslucentCompat = true

        window.statusBarColor = resolveThemedColor(android.R.attr.statusBarColor)
        window.navigationBarColor = resolveThemedColor(android.R.attr.navigationBarColor)

        window.isLightStatusBarsCompat = resolveThemedBoolean(android.R.attr.windowLightStatusBar)

        if (Build.VERSION.SDK_INT >= 27) {
            window.isLightNavigationBarCompat = resolveThemedBoolean(android.R.attr.windowLightNavigationBar)
        }

        this.dayNight = dayNight
    }

    sealed interface Event {
        data object ServiceRecreated : Event
        data object ActivityStart : Event
        data object ActivityStop : Event
        data object ClashStop : Event
        data object ClashStarting : Event
        data object ClashStart : Event
        data object ProfileLoaded : Event
        data class ProfileChanged(val uuid: UUID?) : Event
        data class FreezeMarksChanged(val uuid: UUID?) : Event
        data object HiddenServersChanged : Event
        data object TunnelAppsChanged : Event
    }

    private fun syncAppLocale() {
        AppLocale.current = AppCompatDelegate.getApplicationLocales()[0]

        if (appCompatLocalesLoaded) return

        appCompatLocalesLoaded = true

        val tag = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag().orEmpty()

        if (tag.isEmpty()) return

        // Язык интерфейса ставит AppCompat; здесь только зеркало для служб на
        // Android < 13, поэтому ждать его первому кадру незачем.
        ServiceSettings.write {
            if (appLocale != tag) {
                appLocale = tag
            }
        }
    }
}
