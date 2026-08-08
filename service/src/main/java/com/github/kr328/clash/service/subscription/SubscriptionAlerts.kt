package com.github.kr328.clash.service.subscription

import java.util.concurrent.TimeUnit

/** О чём напоминаем. */
sealed interface SubscriptionAlert {
    /** Подписка кончилась. */
    data object Expired : SubscriptionAlert

    /** До конца подписки осталось [days] дней или меньше. */
    data class ExpiresIn(val days: Int) : SubscriptionAlert

    /** Израсходовано [percent] процентов трафика или больше. */
    data class TrafficUsed(val percent: Int) : SubscriptionAlert
}

/**
 * Когда напоминать о сроке и трафике подписки.
 *
 * Пороги задаёт панель заголовками `notify-expire-days` и
 * `notify-traffic-percent`; ядро кладёт их в `panel.json`. Панель промолчала —
 * берутся умолчания отсюда, панель прислала пустой список — не напоминаем вовсе.
 *
 * Считалка чистая: часы приходят аргументом, состояние «о чём уже сказали» —
 * тоже. Побочных действий нет ни одного, поэтому её поведение проверяется без
 * телефона и без службы.
 */
object SubscriptionAlerts {
    /** За сколько дней до конца напоминать, если панель не сказала иначе. */
    val DEFAULT_EXPIRE_DAYS = listOf(1, 3, 7)

    /** На каких процентах израсходованного трафика напоминать по умолчанию. */
    val DEFAULT_TRAFFIC_PERCENT = listOf(80, 90, 100)

    private val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)

    /** Ключ «о конце подписки уже сказали». */
    private const val EXPIRED_KEY = "expired"

    /**
     * К какому именно сроку относятся отметки семейства «срок». Продление
     * двигает срок — и вся семья отметок обнуляется, иначе о новом сроке
     * не напомнили бы ни разу.
     */
    private const val EXPIRE_BASE_KEY = "expire_base"

    private fun expireKey(days: Int) = "expire_${days}d"

    private fun trafficKey(percent: Int) = "traffic_$percent"

    /**
     * Что известно о подписке на момент проверки.
     *
     * @param expireAt конец подписки в миллисекундах системных часов; 0 — бессрочная.
     * @param total сколько трафика выдано, байт; 0 — безлимит.
     * @param used сколько израсходовано, байт.
     * @param notified о чём уже напоминали: ключ порога → когда сказали.
     */
    data class Snapshot(
        val expireAt: Long,
        val total: Long,
        val used: Long,
        val expireDays: List<Int>,
        val trafficPercent: List<Int>,
        val notified: Map<String, Long>,
    )

    /**
     * @param alerts что показать прямо сейчас — не больше одного напоминания
     *   на семью, самое строгое из пропущенных.
     * @param notified новое состояние, которое надо сохранить.
     */
    data class Outcome(
        val alerts: List<SubscriptionAlert>,
        val notified: Map<String, Long>,
    )

    /**
     * Один проход проверки.
     *
     * Правила, которые тут важнее кода:
     * * на семью показываем ОДНО напоминание — самое строгое из пропущенных,
     *   а остальные молча помечаем сказанными, иначе при первом же запуске
     *   на исчерпанной подписке прилетело бы три уведомления подряд;
     * * порог, из-под которого значение ушло обратно (продлили подписку,
     *   обновили трафик, поправили часы), взводится заново — иначе о настоящем
     *   конце подписки не сказали бы уже никогда;
     * * пороги, которых панель больше не задаёт, теряют состояние: вернётся
     *   такой порог — начнёт с чистого листа.
     */
    fun evaluate(snapshot: Snapshot, nowMillis: Long): Outcome {
        val map = snapshot.notified.toMutableMap()

        val valid = { key: String ->
            key == EXPIRE_BASE_KEY ||
                (key == EXPIRED_KEY && snapshot.expireDays.isNotEmpty()) ||
                snapshot.expireDays.any { key == expireKey(it) } ||
                snapshot.trafficPercent.any { key == trafficKey(it) }
        }
        map.keys.retainAll { valid(it) }

        val alerts = mutableListOf<SubscriptionAlert>()

        // --- срок: только по системным часам, работает и без сети ---
        if (snapshot.expireAt != 0L && snapshot.expireDays.isNotEmpty()) {
            if (map[EXPIRE_BASE_KEY] != snapshot.expireAt) {
                map.keys.retainAll { !it.startsWith("expire_") && it != EXPIRED_KEY }
                map[EXPIRE_BASE_KEY] = snapshot.expireAt
            }

            val remaining = snapshot.expireAt - nowMillis

            if (remaining > 0) {
                map.remove(EXPIRED_KEY)
            }
            for (days in snapshot.expireDays) {
                if (remaining > days * DAY_MILLIS) {
                    map.remove(expireKey(days))
                }
            }

            // Всё, что уже наступило, от самого строгого к мягкому.
            val passed = mutableListOf<Pair<String, SubscriptionAlert?>>()
            if (remaining <= 0) {
                passed += EXPIRED_KEY to SubscriptionAlert.Expired
            }
            for (days in snapshot.expireDays.sorted()) {
                if (remaining > 0 && remaining <= days * DAY_MILLIS) {
                    passed += expireKey(days) to SubscriptionAlert.ExpiresIn(days)
                } else if (remaining <= 0) {
                    // «Кончилась» перекрывает любые «осталось N дней»:
                    // помечаем их сказанными молча.
                    passed += expireKey(days) to null
                }
            }

            var shown = false
            for ((key, alert) in passed) {
                if (!map.containsKey(key)) {
                    if (!shown && alert != null) {
                        alerts += alert
                        shown = true
                    }
                    map[key] = nowMillis
                } else if (alert != null) {
                    // О более строгом пороге уже говорили — значит и более
                    // мягкий новостью быть не может.
                    shown = true
                }
            }
        } else if (snapshot.expireAt == 0L) {
            // Стала бессрочной: состояние семьи больше не к чему привязывать.
            map.keys.retainAll {
                !it.startsWith("expire_") && it != EXPIRED_KEY && it != EXPIRE_BASE_KEY
            }
        }

        // --- трафик ---
        if (snapshot.total > 0 && snapshot.trafficPercent.isNotEmpty()) {
            // Сравниваем перекрёстным умножением, а не процентом: панель вправе
            // прислать любые числа, и `used * 100` на безумном значении ушло бы
            // в минус переполнением, а деление на крошечный `total` не влезло бы
            // в Int. Здесь оба края безопасны.
            val used = snapshot.used.coerceAtLeast(0)
            val reached = { threshold: Int -> percentReached(used, snapshot.total, threshold) }

            for (threshold in snapshot.trafficPercent) {
                if (!reached(threshold)) {
                    map.remove(trafficKey(threshold))
                }
            }

            var shown = false
            for (threshold in snapshot.trafficPercent.filter { reached(it) }.sortedDescending()) {
                val key = trafficKey(threshold)
                if (!map.containsKey(key)) {
                    if (!shown) {
                        alerts += SubscriptionAlert.TrafficUsed(threshold)
                        shown = true
                    }
                    map[key] = nowMillis
                } else {
                    shown = true
                }
            }
        }

        return Outcome(alerts = alerts, notified = map)
    }

    /**
     * Израсходовано ли [threshold] процентов от [total].
     *
     * Целочисленно и без переполнения: `used * 100` на значении от неадекватной
     * панели ушло бы в минус, а процент от крошечного `total` не влез бы в Int.
     */
    private fun percentReached(used: Long, total: Long, threshold: Int): Boolean {
        val whole = used / total * 100
        val rest = used % total * 100 / total

        return whole + rest >= threshold
    }
}
