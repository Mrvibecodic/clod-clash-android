package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.kr328.clash.common.model.DiagnosticsState
import com.github.kr328.clash.design.BuildConfig
import com.github.kr328.clash.design.compose.screen.AppSettingsAction
import com.github.kr328.clash.design.compose.screen.AppSettingsScreen
import com.github.kr328.clash.design.compose.screen.AppSettingsState
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.DiagnosticsCredential
import com.github.kr328.clash.service.store.DiagnosticsCredentialStore
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.store.normalizeDiagnosticsEndpoint
import com.github.kr328.clash.service.util.sendDiagnosticsChanged
import kotlinx.coroutines.launch

class AppSettingsDesign(
    context: Context,
    private val uiStore: UiStore,
    private val srvStore: ServiceStore,
    private val behavior: Behavior,
    private val running: Boolean,
    private val onHideIconChange: (hide: Boolean) -> Unit,
    private val isRunning: () -> Boolean,
    private val onReset: () -> Unit,
    diagnosticsState: DiagnosticsState,
) : Design<AppSettingsDesign.Request>(context) {
    sealed interface Request {
        data object ReCreateAllActivities : Request
        data object OpenSystemNotifications : Request
        data object RequestNotifications : Request
        data object ExportProfiles : Request
        data object ImportProfiles : Request
        data object Back : Request
    }

    private val darkModes = DarkMode.entries
    private val credentials = DiagnosticsCredentialStore(context)
    private val diagnosticsEndpoint = normalizeDiagnosticsEndpoint(srvStore.diagnosticsEndpoint).orEmpty()

    private val languageTags = listOf("", "en", "ru")

    private var state by mutableStateOf(
        AppSettingsState(
            autoRestart = behavior.autoRestart,
            darkMode = darkModes.indexOf(uiStore.darkMode).coerceAtLeast(0),
            language = currentLanguage(),
            showGroupIcons = uiStore.showGroupIcons,
            hideAppIcon = uiStore.hideAppIcon,
            hideFromRecents = uiStore.hideFromRecents,
            dynamicNotification = srvStore.dynamicNotification,
            notificationEditable = !running,
            enableHwid = srvStore.enableHwid,
            subNotifications = srvStore.enableSubNotifications,
            profileErrorNotifications = srvStore.notifyProfileErrors,
            profileUpdateNotifications = srvStore.notifyProfileUpdates,
            notificationsBlocked = notificationsBlocked(),
            resetEnabled = !running,
            diagnosticsEnabled = diagnosticsState != DiagnosticsState.STOPPED,
            diagnosticsAvailable = BuildConfig.DIAGNOSTICS_AVAILABLE,
            diagnosticsConfigured = credentials.read() != null,
            diagnosticsEndpoint = diagnosticsEndpoint,
            vpnServiceRunning = running && uiStore.enableVpn,
            diagnosticsState = diagnosticsState,
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
        credentials.clear()
        context.sendDiagnosticsChanged(false)
        onReset()

        applyLanguage(0)

        state = state.copy(
            autoRestart = behavior.autoRestart,
            darkMode = darkModes.indexOf(uiStore.darkMode).coerceAtLeast(0),
            language = 0,
            showGroupIcons = uiStore.showGroupIcons,
            hideAppIcon = false,
            hideFromRecents = uiStore.hideFromRecents,
            dynamicNotification = srvStore.dynamicNotification,
            enableHwid = srvStore.enableHwid,
            subNotifications = srvStore.enableSubNotifications,
            profileErrorNotifications = srvStore.notifyProfileErrors,
            profileUpdateNotifications = srvStore.notifyProfileUpdates,
            notificationsBlocked = notificationsBlocked(),
            diagnosticsEnabled = false,
            diagnosticsConfigured = false,
            diagnosticsEndpoint = normalizeDiagnosticsEndpoint(srvStore.diagnosticsEndpoint).orEmpty(),
            diagnosticsState = DiagnosticsState.STOPPED,
        )

        requests.trySend(Request.ReCreateAllActivities)
    }

    private fun currentLanguage(): Int {
        val tag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .substringBefore(',')
            .substringBefore('-')
            .lowercase()

        val index = languageTags.indexOf(tag)

        return if (index > 0) index else 0
    }

    private fun applyLanguage(index: Int) {
        val tag = languageTags.getOrNull(index) ?: return

        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            },
        )
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
            AppSettingsAction.ExportProfiles -> {
                requests.trySend(Request.ExportProfiles)
            }
            AppSettingsAction.ImportProfiles -> {
                requests.trySend(Request.ImportProfiles)
            }
            is AppSettingsAction.SetLanguage -> {
                state = state.copy(language = action.index)

                applyLanguage(action.index)
            }
            is AppSettingsAction.SetShowGroupIcons -> {
                uiStore.showGroupIcons = action.enabled

                state = state.copy(showGroupIcons = action.enabled)
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
            is AppSettingsAction.SetProfileUpdateNotifications -> {
                srvStore.notifyProfileUpdates = action.enabled

                state = state.copy(profileUpdateNotifications = action.enabled)

                askNotificationsIfNeeded(action.enabled)
            }
            is AppSettingsAction.SetDynamicNotification -> {
                srvStore.dynamicNotification = action.enabled

                state = state.copy(dynamicNotification = action.enabled)

                askNotificationsIfNeeded(action.enabled)
            }
            is AppSettingsAction.SetDiagnostics -> {
                if (
                    action.enabled &&
                    (
                        !state.diagnosticsAvailable ||
                            !state.diagnosticsConfigured ||
                            state.diagnosticsEndpoint.isBlank() ||
                            !state.vpnServiceRunning
                    )
                ) return

                if (action.enabled && credentials.read() == null) return
                state = state.copy(diagnosticsEnabled = action.enabled)
                context.sendDiagnosticsChanged(action.enabled)
            }
            is AppSettingsAction.SaveDiagnosticsCredential -> {
                if (state.vpnServiceRunning) return
                val endpoint = normalizeDiagnosticsEndpoint(action.endpoint) ?: return
                val replacesCredentials = action.username.isNotBlank() ||
                    action.password.isNotBlank() ||
                    action.controllerSecret.isNotBlank() ||
                    action.remotePort >= 0
                if (!replacesCredentials && !state.diagnosticsConfigured) return

                val replacement = if (replacesCredentials) {
                    DiagnosticsCredential.create(
                        action.username,
                        action.password,
                        action.controllerSecret,
                        action.remotePort,
                    ) ?: return
                } else {
                    null
                }

                val saved = replacement == null || credentials.save(replacement)
                if (!saved) {
                    state = state.copy(diagnosticsEnabled = false)
                    context.sendDiagnosticsChanged(false)
                    return
                }

                srvStore.diagnosticsEndpoint = endpoint
                state = state.copy(
                    diagnosticsEnabled = false,
                    diagnosticsConfigured = credentials.read() != null,
                    diagnosticsEndpoint = endpoint,
                )
                context.sendDiagnosticsChanged(false)
            }
            AppSettingsAction.ClearDiagnosticsCredential -> {
                if (state.vpnServiceRunning) return
                credentials.clear()
                state = state.copy(diagnosticsEnabled = false, diagnosticsConfigured = false)
                context.sendDiagnosticsChanged(false)
            }
        }
    }

    fun updateDiagnosticsStatus(status: DiagnosticsState) {
        state = state.copy(
            diagnosticsEnabled = status != DiagnosticsState.STOPPED,
            diagnosticsState = status,
        )
    }
}
