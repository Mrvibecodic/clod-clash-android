package com.github.kr328.clash.service.store

import android.content.Context
import android.os.SystemClock
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
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

    var enableSubNotifications: Boolean by store.boolean(
        key = "enable_sub_notifications",
        defaultValue = true,
    )

    var notifyProfileErrors: Boolean by store.boolean(
        key = "notify_profile_update_errors",
        defaultValue = true,
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
}
