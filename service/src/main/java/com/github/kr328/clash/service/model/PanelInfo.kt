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
    /** Объявление провайдера (`announce`) и ссылка «подробнее» к нему. */
    val announce: String = "",
    val announceUrl: String = "",
    val supportUrl: String = "",
    val homeUrl: String = "",
    /** `clod-renew-url` — кнопка «Продлить»; пусто означает «кнопки нет». */
    val renewUrl: String = "",
    /** `clod-topup-url` — кнопка «Докупить трафик». */
    val topupUrl: String = "",
    val promo: String = "",
    val promoUrl: String = "",
    /**
     * Состояние устройства: `active`, `limit`, `not-supported` или пусто,
     * если про устройства ничего не пришло. Разбирается ядром
     * (`native/config/panel.go`).
     */
    val hwidState: String = "",

    /** Сколько устройств разрешено подпиской; 0 — не сказано. */
    val hwidMaxDevices: Int = 0,

    /** Когда обновится трафик, в секундах Unix; 0 — не сказано. */
    val refillDate: Long = 0,

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
            renewUrl.isBlank() && topupUrl.isBlank() && groups.isEmpty()
}

@Serializable
data class PanelGroup(
    val name: String = "",
    val type: String = "",
    val proxies: List<String> = emptyList(),
)
