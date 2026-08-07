package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.github.kr328.clash.design.compose.screen.AppSettingsAction
import com.github.kr328.clash.design.compose.screen.AppSettingsScreen
import com.github.kr328.clash.design.compose.screen.AppSettingsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.service.store.ServiceStore

class AppSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    private val behavior: Behavior,
    running: Boolean,
    private val onHideIconChange: (hide: Boolean) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    sealed interface Request {
        /** Тема или режим в недавних сменились — открытые экраны надо пересобрать. */
        data object ReCreateAllActivities : Request
        data object Back : Request
    }

    private val darkModes = DarkMode.entries

    private var state by mutableStateOf(
        AppSettingsState(
            autoRestart = behavior.autoRestart,
            darkMode = darkModes.indexOf(uiStore.darkMode).coerceAtLeast(0),
            hideAppIcon = uiStore.hideAppIcon,
            hideFromRecents = uiStore.hideFromRecents,
            dynamicNotification = srvStore.dynamicNotification,
            // Уведомление собирается при запуске службы: на ходу его состав
            // не поменять, поэтому при работающем туннеле строка погашена.
            notificationEditable = !running,
            enableHwid = srvStore.enableHwid,
        ),
    )

    override val root: View = ComposeView(context).apply {
        setContent {
            ClodClashTheme {
                AppSettingsScreen(state = state, onAction = ::onAction)
            }
        }
    }

    /**
     * Каждое переключение пишется сразу.
     *
     * Так вёл себя и старый экран: настройки применяются по месту, кнопки
     * «сохранить» тут нет и не было. Состояние экрана обновляется отдельно
     * от записи — хранилище читать обратно незачем, а сама запись у части
     * настроек не мгновенная (переключение компонента в PackageManager).
     */
    private fun onAction(action: AppSettingsAction) {
        when (action) {
            AppSettingsAction.Back -> requests.trySend(Request.Back)
            is AppSettingsAction.SetAutoRestart -> {
                behavior.autoRestart = action.enabled

                state = state.copy(autoRestart = action.enabled)
            }
            is AppSettingsAction.SetDarkMode -> {
                val mode = darkModes.getOrNull(action.index) ?: return

                uiStore.darkMode = mode

                state = state.copy(darkMode = action.index)

                // Тему нельзя поменять на месте: она задаётся при создании
                // активити, поэтому пересоздаём все открытые.
                requests.trySend(Request.ReCreateAllActivities)
            }
            is AppSettingsAction.SetHideAppIcon -> {
                uiStore.hideAppIcon = action.enabled

                state = state.copy(hideAppIcon = action.enabled)

                onHideIconChange(action.enabled)
            }
            is AppSettingsAction.SetHideFromRecents -> {
                uiStore.hideFromRecents = action.enabled

                state = state.copy(hideFromRecents = action.enabled)

                requests.trySend(Request.ReCreateAllActivities)
            }
            is AppSettingsAction.SetEnableHwid -> {
                srvStore.enableHwid = action.enabled

                state = state.copy(enableHwid = action.enabled)
            }
            is AppSettingsAction.SetDynamicNotification -> {
                srvStore.dynamicNotification = action.enabled

                state = state.copy(dynamicNotification = action.enabled)
            }
        }
    }
}
