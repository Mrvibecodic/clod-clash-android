package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.design.compose.screen.AppSettingsAction
import com.github.kr328.clash.design.compose.screen.AppSettingsScreen
import com.github.kr328.clash.design.compose.screen.AppSettingsState
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ServiceStore

class AppSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    private val behavior: Behavior,
    private val running: Boolean,
    private val onHideIconChange: (hide: Boolean) -> Unit,
    private val isRunning: () -> Boolean,
    private val onReset: () -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    sealed interface Request {
        data object ReCreateAllActivities : Request
        data object OpenSystemNotifications : Request
        data object RequestNotifications : Request
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
            notificationEditable = !running,
            enableHwid = srvStore.enableHwid,
            subNotifications = srvStore.enableSubNotifications,
            profileErrorNotifications = srvStore.notifyProfileErrors,
            notificationsBlocked = notificationsBlocked(),
            resetEnabled = !running,
        ),
    )

    override val root: View = composeRoot {
        AppSettingsScreen(state = state, onAction = ::onAction)
    }

    fun refreshNotifications() {
        state = state.copy(notificationsBlocked = notificationsBlocked())
    }

    private fun resetSettings() {
        if (isRunning()) {
            state = state.copy(resetEnabled = false)

            launch { showToast(R.string.clod_setting_needs_stop, ToastDuration.Long) }

            return
        }

        behavior.autoRestart = false

        onHideIconChange(false)

        uiStore.reset()
        srvStore.reset()
        onReset()

        state = state.copy(
            autoRestart = behavior.autoRestart,
            darkMode = darkModes.indexOf(uiStore.darkMode).coerceAtLeast(0),
            hideAppIcon = false,
            hideFromRecents = uiStore.hideFromRecents,
            dynamicNotification = srvStore.dynamicNotification,
            enableHwid = srvStore.enableHwid,
            subNotifications = srvStore.enableSubNotifications,
            profileErrorNotifications = srvStore.notifyProfileErrors,
            notificationsBlocked = notificationsBlocked(),
        )

        requests.trySend(Request.ReCreateAllActivities)
    }

    private fun notificationsBlocked(): Boolean {
        if (!uiStore.notificationsAsked)
            return false

        return !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun askNotificationsIfNeeded(enabled: Boolean) {
        if (!enabled || uiStore.notificationsAsked)
            return

        if (NotificationManagerCompat.from(context).areNotificationsEnabled())
            return

        requests.trySend(Request.RequestNotifications)
    }

    private fun onAction(action: AppSettingsAction) {
        when (action) {
            AppSettingsAction.Back -> requests.trySend(Request.Back)
            AppSettingsAction.OpenSystemNotifications ->
                requests.trySend(Request.OpenSystemNotifications)
            AppSettingsAction.ResetSettings -> resetSettings()
            is AppSettingsAction.SetAutoRestart -> {
                behavior.autoRestart = action.enabled

                state = state.copy(autoRestart = action.enabled)
            }
            is AppSettingsAction.SetDarkMode -> {
                val mode = darkModes.getOrNull(action.index) ?: return

                uiStore.darkMode = mode

                state = state.copy(darkMode = action.index)

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
            is AppSettingsAction.SetSubNotifications -> {
                srvStore.enableSubNotifications = action.enabled

                state = state.copy(subNotifications = action.enabled)

                askNotificationsIfNeeded(action.enabled)
            }
            is AppSettingsAction.SetProfileErrorNotifications -> {
                srvStore.notifyProfileErrors = action.enabled

                state = state.copy(profileErrorNotifications = action.enabled)

                askNotificationsIfNeeded(action.enabled)
            }
            is AppSettingsAction.SetDynamicNotification -> {
                srvStore.dynamicNotification = action.enabled

                state = state.copy(dynamicNotification = action.enabled)

                askNotificationsIfNeeded(action.enabled)
            }
        }
    }
}
