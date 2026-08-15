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
     * Рвать ли живые соединения при смене сети (Wi-Fi ↔ LTE и обратно).
     *
     * Соединение, поднятое через исчезнувший интерфейс, всё равно мертво:
     * разница лишь в том, узнает приложение об этом сразу или через таймаут
     * операционной системы — минуту и больше. Всё это время человек видит
     * «интернет есть, а ничего не грузится».
     *
     * По умолчанию включено. Выключать имеет смысл тому, кто качает большими
     * файлами в приложении без докачки: там обрыв заметнее, чем ожидание.
     * Сброс кэша интерфейсов и соединений DNS идёт в любом случае — он ничего
     * не рвёт у человека, а без него не разрешаются имена.
     */
    var resetConnectionsOnNetworkChange: Boolean by store.boolean(
        key = "reset_connections_on_network_change",
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

    /**
     * То же событие, но по монотонным часам (`SystemClock.elapsedRealtime`).
     *
     * Системные часы умеет двигать кто угодно: синхронизация времени после
     * загрузки телефона, ручная правка, оператор связи. Сдвиг на трое суток
     * назад превратит секундный таймер в «73:38:39» — ровно то, что видно
     * у соседних клиентов. Монотонные часы не двигаются никогда, поэтому
     * считаем по ним, а системные оставляем запасным путём.
     *
     * Отсчёт обнуляется при перезагрузке телефона, но это не мешает: после
     * перезагрузки службы нет, а её следующий запуск перепишет обе метки.
     */
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

    /**
     * Счётчик трафика в уведомлении.
     *
     * По умолчанию ВЫКЛЮЧЕН, хотя у CMFA было наоборот: модуль тикает раз
     * в секунду всё время, пока поднят туннель, и каждый тик — запрос
     * статистики у ядра и перерисовка уведомления. Это заметно на батарее,
     * а сама строка нужна не всем; кому нужна — включает её в «Приложении».
     */
    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = false
    )

    /**
     * Отметить подъём туннеля обеими парами часов.
     *
     * Возвращает поставленную метку системных часов: служба держит её у себя,
     * чтобы при остановке снять СВОЮ отметку, а не чужую.
     */
    fun markSessionStarted(): Long {
        val startedAt = System.currentTimeMillis()

        clashStartedAt = startedAt
        clashStartedElapsed = SystemClock.elapsedRealtime()

        return startedAt
    }

    /**
     * Снять отметку сессии при остановке службы.
     *
     * Снимаем, только если в настройках лежит наше собственное значение.
     * Главный случай — служба, которая остановила себя прямо в `onCreate`,
     * увидев уже работающую (так проходит смена режима VPN ↔ только прокси):
     * своей метки она не ставила, а `onDestroy` у неё вызовется, и обнуление
     * вслепую погасило бы таймер живого туннеля.
     */
    fun clearSessionStarted(startedAt: Long) {
        if (startedAt == 0L || clashStartedAt != startedAt)
            return

        clashStartedAt = 0L
        clashStartedElapsed = 0L
    }
}