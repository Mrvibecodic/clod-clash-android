package com.github.kr328.clash.service.model

import kotlinx.serialization.Serializable

/**
 * Что известно о подписке помимо самого конфига.
 *
 * Файл `panel.json` рядом с `config.yaml` в каталоге профиля пишет ядро при
 * загрузке подписки (`native/config/panel.go`). Файлом, а не колонками в базе:
 * заводить миграцию Room на каждый новый заголовок панели — дорого, а живут эти
 * данные ровно столько же, сколько сам профиль, и обновляются вместе с ним.
 *
 * Все поля со значением по умолчанию: старый профиль, загруженный до появления
 * этого файла, читается как пустой, а не роняет разбор.
 */
@Serializable
data class PanelInfo(
    /** Название подписки от панели (`profile-title`). */
    val title: String = "",
    /** `profile-logo` — адрес логотипа провайдера, уже проверенный на https. */
    val logoUrl: String = "",
    /**
     * Имя файла с логотипом в каталоге профиля, если его удалось скачать.
     * Ядро кладёт картинку рядом с `config.yaml` при обновлении подписки:
     * так она не мигает на холодном старте, работает офлайн и не отдаёт
     * чужому хосту адрес человека на каждой отрисовке экрана.
     */
    val logoFile: String = "",
    /** Объявление провайдера (`announce`) и ссылка «подробнее» к нему. */
    val announce: String = "",
    val announceUrl: String = "",
    val supportUrl: String = "",
    val homeUrl: String = "",
    /** `clod-portal-url` — кнопка «Личный кабинет»; пусто означает «кнопки нет». */
    val portalUrl: String = "",
    val promo: String = "",
    val promoUrl: String = "",
    /**
     * Состояние устройства: `active`, `limit`, `not-supported` или пусто,
     * если про устройства ничего не пришло. Разбирается ядром
     * (`native/config/panel.go`).
     */
    val hwidState: String = "",

    /**
     * `clod-hwid-limit` — текст провайдера для карточек «лимит устройств»
     * и «устройство не опознано». Необязательный и отдельный от `announce`:
     * объявление на главной видят все, а это объяснение адресовано одному
     * заблокированному устройству.
     */
    val hwidLimitMessage: String = "",

    /** Сколько устройств разрешено подпиской; 0 — не сказано. */
    val hwidMaxDevices: Int = 0,

    /** Когда обновится трафик, в секундах Unix; 0 — не сказано. */
    val refillDate: Long = 0,

    /**
     * За сколько дней до конца подписки напоминать (`notify-expire-days`)
     * и на каком проценте израсходованного трафика (`notify-traffic-percent`).
     *
     * `null` и пустой список значат РАЗНОЕ. `null` — панель про напоминания
     * ничего не сказала, и клиент берёт свои умолчания. Пустой список — панель
     * напоминания выключила, и молчать надо совсем. Поэтому у полей нет
     * значения-заглушки вроде `emptyList()`.
     */
    val notifyExpireDays: List<Int>? = null,
    val notifyTrafficPercent: List<Int>? = null,

    /**
     * Настоящих серверов не выдано вовсе: пришли одни узлы-обманки, и ядро
     * их выбросило. Пустой список без объяснения читается как поломка
     * приложения, поэтому факт нужен экрану отдельно.
     */
    val noServers: Boolean = false,

    /**
     * Состав конфига: группы и их узлы. Нужен, чтобы показать список серверов
     * ДО подключения — пока туннель не поднят, ядро о группах ничего не знает.
     */
    val groups: List<PanelGroup> = emptyList(),
) {
    val isEmpty: Boolean
        get() = title.isBlank() && announce.isBlank() && promo.isBlank() &&
            portalUrl.isBlank() && logoFile.isBlank() && groups.isEmpty()
}

@Serializable
data class PanelGroup(
    val name: String = "",
    val type: String = "",
    val proxies: List<String> = emptyList(),
)
