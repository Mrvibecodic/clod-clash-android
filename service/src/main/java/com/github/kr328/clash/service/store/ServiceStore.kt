package com.github.kr328.clash.service.store

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.clash.module.ReactionSeries
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.util.KEY_APP_LOCALE
import java.util.*

class ServiceStore(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)

    private val resolver = context.contentResolver

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

    var stickyRestarts: String by store.string(
        key = "sticky_restarts",
        defaultValue = "",
    )

    var resetConnectionsOnNetworkChange: Boolean by store.boolean(
        key = "reset_connections_on_network_change",
        defaultValue = true,
    )

    // Соль ключа сети в отчёте прослойке: своя у каждой установки, чтобы по
    // ключу нельзя было перебором восстановить оператора и страну
    var reportNetSalt: String by store.string(
        key = "report_net_salt",
        defaultValue = "",
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

    var notifyAppUpdates: Boolean by store.boolean(
        key = "notify_app_updates",
        defaultValue = true,
    )

    private var appUpdateAnnouncedRaw: String by store.string(
        key = "app_update_announced",
        defaultValue = "",
    )

    var appUpdateAnnounced: List<Long>
        get() = appUpdateAnnouncedRaw.split(',').mapNotNull { it.toLongOrNull() }
        set(value) {
            appUpdateAnnouncedRaw = value.joinToString(",")
        }

    var vpnAlwaysOn: Int by store.int(
        key = "vpn_always_on",
        defaultValue = -1,
    )

    var clashStartedAt: Long by store.long(
        key = KEY_CLASH_STARTED_AT,
        defaultValue = 0L
    )

    var clashStartedElapsed: Long by store.long(
        key = KEY_CLASH_STARTED_ELAPSED,
        defaultValue = 0L
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptSelected,
        values = AccessControlMode.values()
    )

    var accessControlPackages: Set<String> by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var accessControlApplied: String by store.string(
        key = "access_control_applied",
        defaultValue = ""
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

    fun reset() {
        enableHwid = true
        resetConnectionsOnNetworkChange = true
        enableSubNotifications = true
        notifyProfileErrors = true
        notifyProfileUpdates = true
        notifyAppUpdates = true
        bypassPrivateNetwork = true
        accessControlMode = AccessControlMode.AcceptSelected
        accessControlPackages = emptySet()
        dnsHijacking = true
        systemProxy = true
        allowBypass = false
        allowIpv6 = false
        tunStackMode = "auto"
        dynamicNotification = false
        keepAwake = false
        appLocale = ""
    }

    fun markSessionStarted(): Long {
        val startedAt = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()

        // Отметка ставится перед самым подъёмом туннеля, и отложенная запись теряется,
        // если процесс не переживёт старт: пишем синхронно и обе величины разом.
        preferences.edit(commit = true) {
            putLong(KEY_CLASH_STARTED_AT, startedAt)
            putLong(KEY_CLASH_STARTED_ELAPSED, elapsed)
        }

        return startedAt
    }

    fun clearSessionStarted(startedAt: Long) {
        if (startedAt == 0L || clashStartedAt != startedAt)
            return

        clashStartedAt = 0L
        clashStartedElapsed = 0L
    }

    // Marks are elapsedRealtime values and only meaningful within one boot,
    // so they are stored together with a boot id and dropped after a reboot
    internal fun recordStickyRestart(now: Long, windowMs: Long, limit: Int): ReactionSeries.Mark {
        val boot = bootId()
        val stored = stickyRestarts.split('|', limit = 2)
        val previous = if (stored.size == 2 && stored[0] == boot) stored[1] else ""
        val series = ReactionSeries(windowMs, limit, previous.split(',').mapNotNull { it.toLongOrNull() })
        val mark = series.mark(now)

        stickyRestarts = boot + "|" + series.recorded.joinToString(",")

        return mark
    }

    private fun bootId(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT) }
                .getOrNull()
                ?.let { return "boot$it" }
        }

        return "at" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L
    }

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_profile"

        private const val KEY_CLASH_STARTED_AT = "clash_started_at"

        private const val KEY_CLASH_STARTED_ELAPSED = "clash_started_elapsed"
    }
}
