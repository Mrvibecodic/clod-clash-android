package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActivityScaffold
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.SelectRow
import com.github.kr328.clash.design.compose.component.SwitchRow

@Immutable
data class AppSettingsState(
    val autoRestart: Boolean = false,
    val darkMode: Int = 0,
    val hideAppIcon: Boolean = false,
    val hideFromRecents: Boolean = false,
    val dynamicNotification: Boolean = false,
    val notificationEditable: Boolean = true,
    val enableHwid: Boolean = true,
    val subNotifications: Boolean = true,
    val profileErrorNotifications: Boolean = true,
    val notificationsBlocked: Boolean = false,
)

sealed interface AppSettingsAction {
    data object Back : AppSettingsAction
    data class SetAutoRestart(val enabled: Boolean) : AppSettingsAction
    data class SetDarkMode(val index: Int) : AppSettingsAction
    data class SetHideAppIcon(val enabled: Boolean) : AppSettingsAction
    data class SetHideFromRecents(val enabled: Boolean) : AppSettingsAction
    data class SetDynamicNotification(val enabled: Boolean) : AppSettingsAction
    data class SetEnableHwid(val enabled: Boolean) : AppSettingsAction
    data class SetSubNotifications(val enabled: Boolean) : AppSettingsAction
    data class SetProfileErrorNotifications(val enabled: Boolean) : AppSettingsAction
    data object OpenSystemNotifications : AppSettingsAction
}

@Composable
fun AppSettingsScreen(
    state: AppSettingsState,
    onAction: (AppSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityScaffold(
        title = stringResource(R.string.app),
        onBack = { onAction(AppSettingsAction.Back) },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.behavior))
            SwitchRow(
                title = stringResource(R.string.auto_restart),
                subtitle = stringResource(R.string.allow_clash_auto_restart),
                icon = painterResource(R.drawable.ic_baseline_restore),
                checked = state.autoRestart,
                onCheckedChange = { onAction(AppSettingsAction.SetAutoRestart(it)) },
            )

            SectionHeader(stringResource(R.string.interface_))
            SelectRow(
                title = stringResource(R.string.dark_mode),
                icon = painterResource(R.drawable.ic_baseline_brightness_4),
                options = listOf(
                    stringResource(R.string.follow_system_android_10),
                    stringResource(R.string.always_light),
                    stringResource(R.string.always_dark),
                ),
                selectedIndex = state.darkMode,
                onSelect = { onAction(AppSettingsAction.SetDarkMode(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.hide_app_icon_title),
                subtitle = stringResource(R.string.hide_app_icon_desc),
                icon = painterResource(R.drawable.ic_baseline_hide),
                checked = state.hideAppIcon,
                onCheckedChange = { onAction(AppSettingsAction.SetHideAppIcon(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.hide_from_recents_title),
                subtitle = stringResource(R.string.hide_from_recents_desc),
                icon = painterResource(R.drawable.ic_baseline_stack),
                checked = state.hideFromRecents,
                onCheckedChange = { onAction(AppSettingsAction.SetHideFromRecents(it)) },
            )

            SectionHeader(stringResource(R.string.service))
            if (state.notificationsBlocked) {
                BlockedNotice(onAction)
            }
            SwitchRow(
                title = stringResource(R.string.show_traffic),
                subtitle = if (state.notificationEditable) {
                    stringResource(R.string.show_traffic_summary)
                } else {
                    stringResource(R.string.clod_setting_needs_stop)
                },
                icon = painterResource(R.drawable.ic_baseline_domain),
                checked = state.dynamicNotification,
                enabled = state.notificationEditable && !state.notificationsBlocked,
                onCheckedChange = { onAction(AppSettingsAction.SetDynamicNotification(it)) },
            )

            SectionHeader(stringResource(R.string.clod_tab_subscriptions))
            SwitchRow(
                title = stringResource(R.string.clod_hwid_title),
                subtitle = stringResource(R.string.clod_hwid_summary),
                icon = painterResource(R.drawable.ic_baseline_key),
                checked = state.enableHwid,
                onCheckedChange = { onAction(AppSettingsAction.SetEnableHwid(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.clod_sub_notify_title),
                subtitle = stringResource(R.string.clod_sub_notify_summary),
                icon = painterResource(R.drawable.ic_baseline_notifications),
                checked = state.subNotifications,
                enabled = !state.notificationsBlocked,
                onCheckedChange = { onAction(AppSettingsAction.SetSubNotifications(it)) },
            )
            SwitchRow(
                title = stringResource(R.string.clod_profile_error_notify_title),
                subtitle = stringResource(R.string.clod_profile_error_notify_summary),
                icon = painterResource(R.drawable.ic_outline_info),
                checked = state.profileErrorNotifications,
                enabled = !state.notificationsBlocked,
                onCheckedChange = { onAction(AppSettingsAction.SetProfileErrorNotifications(it)) },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BlockedNotice(onAction: (AppSettingsAction) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.clod_notify_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onAction(AppSettingsAction.OpenSystemNotifications) }) {
                Text(stringResource(R.string.clod_notify_open_settings))
            }
        }
    }
}
