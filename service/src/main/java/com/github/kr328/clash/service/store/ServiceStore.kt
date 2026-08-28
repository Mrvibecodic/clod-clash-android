package com.github.kr328.clash.service.store

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.util.KEY_APP_LOCALE
import java.util.*

class ServiceStore(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)

    private val store = Store(preferences.asStoreProvider())

    var activeProfile: UUID? by store.typedString(
        key = KEY_ACTIVE_PROFILE,
        from = { value ->
            if (value.isBlank()) {
                null
            } else {
                runCatching { UUID.fromString(value) }
                    .onFailure {
                        Log.w("Active profile: invalid value \"$value\" dropped")

                        preferences.edit { remove(KEY_ACTIVE_PROFILE) }
                    }
                    .getOrNull()
            }
        },
        to = { it?.toString() ?: "" }
    )

    var enableHwid: Boolean by store.boolean(
        key = "enable_hwid",
        defaultValue = true,
    )

    var resetConnectionsOnNetworkChange: Boolean by store.boolean(
        key = "reset_connections_on_network_change",
        defaultValue = true,
    )

    var hwid: String by store.string(
        key = "hwid",
        defaultValue = "",
    )

    var appLocale: String by store.string(
        key = KEY_APP_LOCALE,
        defaultValue = "",
    )

    var enableSubNotifications: Boolean by store.boolean(
        key = "enable_sub_notifications",
        defaultValue = true,
    )

    var notifyProfileErrors: Boolean by store.boolean(
        key = "notify_profile_update_errors",
        defaultValue = true,
    )

    var notifyProfileUpdates: Boolean by store.boolean(
        key = "notify_profile_update_success",
        defaultValue = true,
    )

    var vpnAlwaysOn: Int by store.int(
        key = "vpn_always_on",
        defaultValue = -1,
    )

    var clashStartedAt: Long by store.long(
        key = "clash_started_at",
        defaultValue = 0L
    )

    var clashStartedElapsed: Long by store.long(
        key = "clash_started_elapsed",
        defaultValue = 0L
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = false
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "auto"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = false
    )

    var keepAwake by store.boolean(
        key = "keep_awake",
        defaultValue = false
    )

    var diagnosticsEndpoint by store.string(
        key = "diagnostics_endpoint",
        defaultValue = DEFAULT_DIAGNOSTICS_ENDPOINT,
    )

    fun reset() {
        enableHwid = true
        resetConnectionsOnNetworkChange = true
        enableSubNotifications = true
        notifyProfileErrors = true
        notifyProfileUpdates = true
        bypassPrivateNetwork = true
        accessControlMode = AccessControlMode.AcceptAll
        accessControlPackages = emptySet()
        dnsHijacking = true
        systemProxy = true
        allowBypass = false
        allowIpv6 = false
        tunStackMode = "auto"
        dynamicNotification = false
        keepAwake = false
        appLocale = ""
        diagnosticsEndpoint = DEFAULT_DIAGNOSTICS_ENDPOINT
    }

    fun markSessionStarted(): Long {
        val startedAt = System.currentTimeMillis()

        clashStartedAt = startedAt
        clashStartedElapsed = SystemClock.elapsedRealtime()

        return startedAt
    }

    fun clearSessionStarted(startedAt: Long) {
        if (startedAt == 0L || clashStartedAt != startedAt)
            return

        clashStartedAt = 0L
        clashStartedElapsed = 0L
    }

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_profile"
    }
}
