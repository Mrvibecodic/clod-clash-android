package com.github.kr328.clash.service.util

import java.util.concurrent.TimeUnit

object UpdateSchedule {
    val MIN_INTERVAL: Long = TimeUnit.MINUTES.toMillis(15)

    fun retryDelay(interval: Long, attempt: Int): Long? {
        if (interval < MIN_INTERVAL)
            return null

        return (MIN_INTERVAL shl (attempt - 1).coerceIn(0, 16))
            .coerceIn(MIN_INTERVAL, interval.coerceAtLeast(MIN_INTERVAL))
    }

    fun retryWithinPeriod(interval: Long, attempt: Int): Boolean {
        val delay = retryDelay(interval, attempt) ?: return false

        return delay < interval
    }

    fun effectiveInterval(manual: Boolean, panel: Long?, own: Long): Long {
        if (manual || panel == null)
            return own

        return panel
    }

    fun firstDelay(interval: Long, updatedAt: Long, now: Long): Long {
        if (updatedAt <= 0)
            return 0

        return (interval - (now - updatedAt)).coerceIn(0, interval)
    }

    /**
     * clod: запас после срока из `subscription-userinfo`, прежде чем спрашивать
     * панель заново. Remnawave переводит пользователя в EXPIRED не в момент
     * истечения, а кроном раз в 30 с (`FIND_EXPIRED_USERS`); запрос ровно в срок
     * получил бы ещё прежний конфиг.
     */
    val EXPIRY_GRACE: Long = TimeUnit.SECONDS.toMillis(90)

    /**
     * Поправка часов снимается из заголовка `Date` с точностью до секунды и между
     * двумя загрузками дрожит на ±1 с; загрузка за считанные секунды до дедлайна —
     * это загрузка в срок, иначе за ней сразу шла бы вторая. Много меньше [EXPIRY_GRACE].
     */
    val EXPIRY_SLACK: Long = TimeUnit.SECONDS.toMillis(5)

    /**
     * clod: момент (часы устройства, мс), к которому подписку надо загрузить заново
     * из-за названного панелью срока; null — срока нет или после него уже загружали.
     *
     * [expire] — срок по часам панели (мс; секунды старых записей нормализуются),
     * [clockSkew] — панель минус устройство (мс, [com.github.kr328.clash.service.model.PanelInfo.clockSkewMillis]),
     * [updatedAt] — mtime `config.yaml`. Признак «после истечения ещё не загружали»
     * нигде не хранится — он выводится из этих трёх величин, поэтому переживает
     * перезагрузку и смерть процесса сам собой: условие снова истинно, и задача
     * ставится заново. Удачная загрузка сдвигает mtime за дедлайн, и повторов нет;
     * продление приносит новый срок.
     *
     * Старение поправки (через 30 дней без загрузок — 0) цель не теряет: если
     * задача взведена по нулевой поправке, а устройство спешит, загрузка уйдёт до
     * срока по часам панели, но её же ответ принесёт свежий замер, и по нему та же
     * загрузка окажется «до дедлайна» — цель переставится уже по свежей поправке.
     */
    fun expiryFetchAt(expire: Long, clockSkew: Long, updatedAt: Long): Long? {
        val expireMillis = if (expire in 1 until MILLIS_THRESHOLD) expire * 1000 else expire
        if (expireMillis <= 0)
            return null

        val deadline = expireMillis - clockSkew + EXPIRY_GRACE

        return deadline.takeIf { updatedAt + EXPIRY_SLACK < it }
    }

    private const val MILLIS_THRESHOLD = 1_000_000_000_000L
}
