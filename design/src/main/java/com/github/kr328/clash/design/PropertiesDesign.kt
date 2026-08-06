package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.compose.screen.FetchProgress
import com.github.kr328.clash.design.compose.screen.PropertiesAction
import com.github.kr328.clash.design.compose.screen.PropertiesScreen
import com.github.kr328.clash.design.compose.screen.PropertiesState
import com.github.kr328.clash.design.compose.screen.isHttpUrl
import com.github.kr328.clash.design.compose.screen.isValidInterval
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PropertiesDesign(context: Context) : Design<PropertiesDesign.Request>(context) {
    sealed interface Request {
        data object Commit : Request
        data object BrowseFiles : Request
        data object Back : Request
    }

    private var state by mutableStateOf(PropertiesState())

    /**
     * Профиль в том виде, в каком он пришёл. Правки живут в состоянии экрана,
     * а сюда возвращаются только через [profile]: так поля, которых на экране
     * нет (uuid, тип, время обновления), не теряются при копировании.
     */
    private var base: Profile? = null

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                PropertiesScreen(state = state, onAction = ::onAction)
            }
        }
    }

    /**
     * Текущее значение полей. `PropertiesActivity` сравнивает его с исходным,
     * чтобы понять, было ли что менять, — поэтому геттер собирается
     * из состояния экрана, а не отдаёт то, что положили.
     */
    var profile: Profile
        get() = checkNotNull(base) { "profile is not set" }.copy(
            name = state.name,
            source = state.url,
            ageSecretKey = state.ageSecretKey.ifBlank { null },
            interval = TimeUnit.MINUTES.toMillis(state.intervalMinutes.toLongOrNull() ?: 0),
        )
        set(value) {
            base = value

            val minutes = TimeUnit.MILLISECONDS.toMinutes(value.interval)

            state = state.copy(
                name = value.name,
                url = value.source,
                ageSecretKey = value.ageSecretKey ?: "",
                // Ноль — это «выключено», а не «раз в ноль минут»: поле
                // остаётся пустым, и подсказка объясняет, что будет.
                intervalMinutes = if (minutes == 0L) "" else minutes.toString(),
                // Только подписка по ссылке. У внешнего профиля в source лежит
                // адрес, выданный приложением-источником: старый экран открыть
                // его на правку тоже не давал.
                urlEditable = value.type == Profile.Type.Url,
                intervalEditable = value.type != Profile.Type.File,
            )
        }

    val progressing: Boolean
        get() = state.processing != null

    /**
     * Черновик пригоден к записи.
     *
     * Нужен, потому что поля правятся напрямую: пока их правили в модальных
     * окнах с валидаторами, недописанное значение до профиля просто не
     * доходило. Теперь дойдёт — и уход с экрана сохранил бы пустое имя или
     * недописанную ссылку молча.
     *
     * Ключ age сюда не входит: его проверяет ядро, и это отдельный вызов
     * через JNI, а не свойство.
     */
    val draftValid: Boolean
        get() = state.name.isNotBlank() &&
            (!state.urlEditable || isHttpUrl(state.url)) &&
            isValidInterval(state.intervalMinutes)

    private fun onAction(action: PropertiesAction) {
        when (action) {
            PropertiesAction.Back -> request(Request.Back)
            PropertiesAction.Commit -> request(Request.Commit)
            PropertiesAction.BrowseFiles -> request(Request.BrowseFiles)
            is PropertiesAction.NameChanged -> state = state.copy(name = action.value)
            is PropertiesAction.UrlChanged -> state = state.copy(url = action.value)
            is PropertiesAction.AgeSecretKeyChanged ->
                state = state.copy(ageSecretKey = action.value)

            is PropertiesAction.IntervalChanged ->
                // Отсеиваем всё, кроме цифр: на части клавиатур числовой режим
                // — рекомендация, а не запрет, и запятая или минус превратили бы
                // интервал в ноль молча.
                state = state.copy(intervalMinutes = action.value.filter { it.isDigit() })

            PropertiesAction.ConfirmExit -> resumeExit(true)
            PropertiesAction.CancelExit -> resumeExit(false)
        }
    }

    suspend fun withProcessing(executeTask: suspend (suspend (FetchStatus) -> Unit) -> Unit) {
        try {
            setProgress(FetchProgress(context.getString(R.string.initializing)))

            executeTask { setProgress(it.toProgress()) }
        } finally {
            setProgress(null)
        }
    }

    private suspend fun setProgress(progress: FetchProgress?) {
        withContext(Dispatchers.Main) {
            state = state.copy(processing = progress)
        }
    }

    /**
     * Вопрос «выйти без сохранения?». Решение принимает активити — она одна
     * знает, менялся ли профиль, — поэтому экран показывает окно, а ответ
     * возвращается сюда же.
     */
    private var exitConfirmation: CancellableContinuation<Boolean>? = null

    suspend fun requestExitWithoutSaving(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                exitConfirmation = continuation

                state = state.copy(confirmingExit = true)

                continuation.invokeOnCancellation {
                    exitConfirmation = null

                    state = state.copy(confirmingExit = false)
                }
            }
        }
    }

    private fun resumeExit(confirmed: Boolean) {
        state = state.copy(confirmingExit = false)

        val continuation = exitConfirmation ?: return

        exitConfirmation = null

        if (continuation.isActive) {
            continuation.resumeWith(Result.success(confirmed))
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun FetchStatus.toProgress(): FetchProgress = when (action) {
        FetchStatus.Action.FetchConfiguration -> FetchProgress(
            text = context.getString(R.string.format_fetching_configuration, args[0]),
        )
        FetchStatus.Action.FetchProviders -> FetchProgress(
            text = context.getString(R.string.format_fetching_provider, args[0]),
            progress = fraction(),
        )
        FetchStatus.Action.Verifying -> FetchProgress(
            text = context.getString(R.string.verifying),
            progress = fraction(),
        )
        // Данные подписки приходят отдельным событием и к ходу работы
        // отношения не имеют: текст не меняем.
        FetchStatus.Action.SubscriptionInfo -> state.processing
            ?: FetchProgress(context.getString(R.string.initializing))
    }

    private fun FetchStatus.fraction(): Float =
        if (max > 0) progress.toFloat() / max else -1f
}
