package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.compose.screen.AddProfileAction
import com.github.kr328.clash.design.compose.screen.AddProfileScreen
import com.github.kr328.clash.design.compose.screen.AddProfileState
import com.github.kr328.clash.design.compose.screen.AddProfileStep
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Добавление подписки: одно поле, потом — что нашлось по ссылке.
 *
 * Старый экран CMFA сначала спрашивал тип источника, потом вёл в редактор свойств,
 * где имя и интервал обновления надо было заполнить руками. Всё это приходит
 * в ответе панели, поэтому шагов остаётся два.
 */
class AddProfileDesign(context: Context) : Design<AddProfileDesign.Request>(context) {
    sealed interface Request {
        data class Submit(val url: String) : Request
        data object ScanQr : Request
        data object OtherWays : Request
        data object Finish : Request
    }

    private var state by mutableStateOf(AddProfileState())

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                AddProfileScreen(state = state, onAction = ::onAction)
            }
        }
    }

    private fun onAction(action: AddProfileAction) {
        when (action) {
            // Ошибка снимается при первой же правке: держать её на экране, пока
            // человек уже исправляет ссылку, — раздражать без пользы.
            is AddProfileAction.UrlChanged -> state = state.copy(url = action.url, error = null)
            AddProfileAction.Submit -> requests.trySend(Request.Submit(state.url))
            AddProfileAction.ScanQr -> requests.trySend(Request.ScanQr)
            AddProfileAction.OtherWays -> requests.trySend(Request.OtherWays)
            AddProfileAction.Finish -> requests.trySend(Request.Finish)
        }
    }

    /** Подставить ссылку снаружи — например, после сканирования QR-кода. */
    suspend fun setUrl(url: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(url = url, error = null)
        }
    }

    suspend fun setFetching() {
        withContext(Dispatchers.Main) {
            state = state.copy(step = AddProfileStep.Fetching, progressText = "", progress = 0f)
        }
    }

    /**
     * Прогресс загрузки в терминах ядра. Строки взяты те же, что показывал старый
     * экран свойств, чтобы человек видел знакомые формулировки.
     */
    suspend fun setProgress(status: FetchStatus) {
        val text = when (status.action) {
            FetchStatus.Action.FetchConfiguration ->
                context.getString(R.string.format_fetching_configuration, status.args.firstOrNull().orEmpty())

            FetchStatus.Action.FetchProviders ->
                context.getString(R.string.format_fetching_provider, status.args.firstOrNull().orEmpty())

            FetchStatus.Action.Verifying -> context.getString(R.string.verifying)
            // SubscriptionInfo — это не шаг загрузки, а разбор заголовка панели:
            // показывать его отдельной строкой не о чем.
            FetchStatus.Action.SubscriptionInfo -> null
        } ?: return

        val progress = if (status.max > 0) status.progress.toFloat() / status.max else 0f

        withContext(Dispatchers.Main) {
            state = state.copy(progressText = text, progress = progress)
        }
    }

    suspend fun setDone(profile: Profile, title: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(step = AddProfileStep.Done, result = profile, resultTitle = title)
        }
    }

    suspend fun setError(message: String) {
        withContext(Dispatchers.Main) {
            state = state.copy(step = AddProfileStep.Input, error = message)
        }
    }
}
