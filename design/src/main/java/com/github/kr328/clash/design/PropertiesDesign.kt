package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.compose.screen.FetchProgress
import com.github.kr328.clash.design.compose.screen.PropertiesAction
import com.github.kr328.clash.design.compose.screen.PropertiesScreen
import com.github.kr328.clash.design.compose.screen.PropertiesState
import com.github.kr328.clash.design.compose.screen.isValidSource
import com.github.kr328.clash.design.compose.screen.nameField
import com.github.kr328.clash.design.compose.screen.withNameField
import com.github.kr328.clash.design.model.ChannelStage
import com.github.kr328.clash.design.util.Confirmation
import com.github.kr328.clash.design.util.ValidatorAutoUpdateInterval
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PropertiesDesign(context: Context) : Design<PropertiesDesign.Request>(context) {
    sealed interface Request {
        data object Commit : Request
        data object BrowseFiles : Request
        data object Back : Request
    }

    private var state by mutableStateOf(PropertiesState())

    private var base: Profile? = null

    override val root: View = composeRoot {
        PropertiesScreen(state = state, onAction = ::onAction)
    }

    var panelName: String? = null

    // Отпечаток ключа прослойки у добавленной подписки; пусто — не загружалась каналом.
    var chanKey: String = ""

    var profile: Profile
        get() = checkNotNull(base) { "profile is not set" }.withNameField(state.name).copy(
            source = state.url,
            interval = TimeUnit.MINUTES.toMillis(state.intervalMinutes.toLongOrNull() ?: 0),
            intervalManual = state.intervalManual,
            secure = state.secure,
        )
        set(value) {
            base = value

            val minutes = TimeUnit.MILLISECONDS.toMinutes(value.interval)

            state = state.copy(
                name = value.nameField,
                nameShown = panelName?.takeIf { it.isNotBlank() } ?: value.name,
                url = value.source,
                intervalMinutes = if (minutes == 0L) "" else minutes.toString(),
                intervalManual = value.intervalManual,
                type = value.type,
                secure = value.secure,
                secureEditable = value.type == Profile.Type.Url && value.imported,
                chanKey = chanKey,
            )
        }

    val progressing: Boolean
        get() = state.processing != null

    val draftValid: Boolean
        get() = isValidSource(state.type, state.url) &&
            ValidatorAutoUpdateInterval(state.intervalMinutes)

    private fun onAction(action: PropertiesAction) {
        when (action) {
            PropertiesAction.Back -> request(Request.Back)
            PropertiesAction.Commit -> request(Request.Commit)
            PropertiesAction.BrowseFiles -> request(Request.BrowseFiles)
            is PropertiesAction.NameChanged -> state = state.copy(name = action.value)
            is PropertiesAction.UrlChanged -> state = state.copy(url = action.value)
            is PropertiesAction.IntervalChanged -> {
                val minutes = action.value.filter { it.isDigit() }

                if (minutes != state.intervalMinutes) {
                    state = state.copy(intervalMinutes = minutes, intervalManual = true)
                }
            }

            PropertiesAction.IntervalFromPanel -> state = state.copy(intervalManual = false)

            PropertiesAction.ConfirmExit -> exitConfirmation.resume(true)
            PropertiesAction.CancelExit -> exitConfirmation.resume(false)
            // Включение проверит сохранение; выключение — только после предупреждения.
            is PropertiesAction.SecureChanged -> state = if (action.on) {
                state.copy(secure = true)
            } else {
                state.copy(confirmingSecureOff = true)
            }
            PropertiesAction.ConfirmSecureOff -> state = state.copy(secure = false, confirmingSecureOff = false)
            PropertiesAction.CancelSecureOff -> state = state.copy(confirmingSecureOff = false)
        }
    }

    suspend fun setImporting(status: FetchStatus?, stage: ChannelStage? = null) {
        val progress = status?.toProgress() ?: FetchProgress(context.getString(R.string.initializing))

        setProgress(progress.copy(stage = stage?.let(::stageText).orEmpty()))
    }

    // Канал включали, а он не ответил: переключатель обратно в «выкл».
    suspend fun setSecure(on: Boolean) {
        withContext(Dispatchers.Main) {
            state = state.copy(secure = on)
        }
    }

    private fun stageText(stage: ChannelStage): String = when (stage) {
        ChannelStage.Checking -> context.getString(R.string.clod_chan_stage_checking)
        is ChannelStage.Retry -> context.getString(R.string.clod_chan_stage_retry, stage.attempt, stage.total)
        ChannelStage.Plain -> context.getString(R.string.clod_chan_stage_plain)
    }

    suspend fun clearImporting() {
        setProgress(null)
    }

    private suspend fun setProgress(progress: FetchProgress?) {
        withContext(Dispatchers.Main) {
            state = state.copy(processing = progress)
        }
    }

    private val exitConfirmation = Confirmation { state = state.copy(confirmingExit = it) }

    suspend fun requestExitWithoutSaving(): Boolean = exitConfirmation.request()

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun FetchStatus.toProgress(): FetchProgress = when (action) {
        FetchStatus.Action.FetchConfiguration -> FetchProgress(
            text = context.getString(R.string.format_fetching_configuration, args[0]),
        )
        FetchStatus.Action.FetchProviders -> FetchProgress(
            text = context.getString(R.string.format_fetching_provider, args.firstOrNull().orEmpty()),
            progress = fraction(),
        )
        FetchStatus.Action.ProviderFailed -> FetchProgress(
            text = context.getString(R.string.clod_provider_failed, args.firstOrNull().orEmpty()),
            progress = fraction(),
        )
        FetchStatus.Action.Verifying -> FetchProgress(
            text = context.getString(R.string.verifying),
            progress = fraction(),
        )
        FetchStatus.Action.SubscriptionInfo -> state.processing
            ?: FetchProgress(context.getString(R.string.initializing))
    }

    private fun FetchStatus.fraction(): Float =
        if (max > 0) progress.toFloat() / max else -1f
}
