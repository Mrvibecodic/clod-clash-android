package com.github.kr328.clash.service.store

import android.content.Context
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

    /**
     * Опознавать ли устройство перед панелью (семейство `x-hwid`).
     *
     * По умолчанию включено: без него подписка с лимитом устройств ведёт себя
     * непредсказуемо — панель не знает, кому отдаёт конфигурацию. Выключать
     * имеет смысл ровно в одном случае: панель лимит не считает, а лишние
     * заголовки человеку не нужны.
     */
    var enableHwid: Boolean by store.boolean(
        key = "enable_hwid",
        defaultValue = true,
    )

    /**
     * ЗАПАСНОЙ идентификатор устройства — только для прошивок, где пуст
     * `Settings.Secure.ANDROID_ID`.
     *
     * В обычном случае идентификатор считается на лету и здесь не лежит:
     * сохранённое значение уехало бы на новый телефон вместе с автоматической
     * резервной копией Android, и два устройства заняли бы одно место в лимите.
     */
    var hwid: String by store.string(
        key = "hwid",
        defaultValue = "",
    )

    /**
     * Напоминать ли о сроке и трафике подписки.
     *
     * Тумблер человека старше настроек панели: провайдер задаёт ПОРОГИ, а
     * решение, хочет ли человек уведомления вообще, остаётся за ним.
     */
    var enableSubNotifications: Boolean by store.boolean(
        key = "enable_sub_notifications",
        defaultValue = true,
    )

    /**
     * Когда туннель подняли, в миллисекундах системных часов.
     *
     * Пишет служба, читает экран: таймер сессии должен быть верным и после того,
     * как приложение закрыли и открыли заново. Считать от запуска Activity —
     * значит показывать неправду при каждом возврате на экран.
     */
    var clashStartedAt: Long by store.long(
        key = "clash_started_at",
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
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "system"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )
}