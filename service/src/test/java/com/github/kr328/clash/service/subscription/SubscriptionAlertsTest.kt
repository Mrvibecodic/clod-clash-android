package com.github.kr328.clash.service.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Проверки счётчика напоминаний о сроке и трафике подписки.
 *
 * Считалка чистая — часы и состояние приходят аргументами, — поэтому здесь
 * проверяется ровно то поведение, которое иначе видно только на телефоне
 * с настоящей подпиской и настоящим будильником: что на семью приходит одно
 * уведомление, что пороги взводятся обратно при продлении и пополнении
 * и что панель может напоминания выключить совсем.
 */
class SubscriptionAlertsTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_700_000_000_000L

    private fun snapshot(
        expireAt: Long = 0,
        total: Long = 0,
        used: Long = 0,
        expireDays: List<Int> = SubscriptionAlerts.DEFAULT_EXPIRE_DAYS,
        trafficPercent: List<Int> = SubscriptionAlerts.DEFAULT_TRAFFIC_PERCENT,
        notified: Map<String, Long> = emptyMap(),
    ) = SubscriptionAlerts.Snapshot(
        expireAt = expireAt,
        total = total,
        used = used,
        expireDays = expireDays,
        trafficPercent = trafficPercent,
        notified = notified,
    )

    // --- ничего не происходит ---

    @Test
    fun `бессрочная подписка без лимита трафика молчит`() {
        val outcome = SubscriptionAlerts.evaluate(snapshot(), now)

        assertEquals(emptyList<SubscriptionAlert>(), outcome.alerts)
        assertTrue(outcome.notified.isEmpty())
    }

    @Test
    fun `до порога далеко — тишина, но состояние не копится`() {
        val outcome = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 30 * day, total = 100, used = 10),
            now,
        )

        assertEquals(emptyList<SubscriptionAlert>(), outcome.alerts)
        // Единственная отметка — к какому сроку относится семья.
        assertEquals(setOf("expire_base"), outcome.notified.keys)
    }

    @Test
    fun `пустой список порогов от панели выключает напоминания`() {
        val outcome = SubscriptionAlerts.evaluate(
            snapshot(
                expireAt = now + day / 2,
                total = 100,
                used = 100,
                expireDays = emptyList(),
                trafficPercent = emptyList(),
            ),
            now,
        )

        assertEquals(emptyList<SubscriptionAlert>(), outcome.alerts)
    }

    // --- срок ---

    @Test
    fun `первым срабатывает самый мягкий из пройденных порогов`() {
        val outcome = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 5 * day),
            now,
        )

        assertEquals(listOf(SubscriptionAlert.ExpiresIn(7)), outcome.alerts)
    }

    @Test
    fun `повторный проход по тому же порогу молчит`() {
        val first = SubscriptionAlerts.evaluate(snapshot(expireAt = now + 5 * day), now)
        val second = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 5 * day, notified = first.notified),
            now + TimeUnit.HOURS.toMillis(1),
        )

        assertEquals(emptyList<SubscriptionAlert>(), second.alerts)
    }

    @Test
    fun `следующий порог напоминает заново`() {
        val first = SubscriptionAlerts.evaluate(snapshot(expireAt = now + 5 * day), now)
        val second = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 5 * day, notified = first.notified),
            now + 3 * day,
        )

        assertEquals(listOf(SubscriptionAlert.ExpiresIn(3)), second.alerts)
    }

    @Test
    fun `на исчерпанной подписке первый же проход даёт ОДНО уведомление`() {
        val outcome = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now - day),
            now,
        )

        assertEquals(listOf(SubscriptionAlert.Expired), outcome.alerts)
        // Пороги «осталось N дней» помечены сказанными молча — иначе следующий
        // проход выдал бы их подряд поверх «подписка кончилась».
        assertTrue(outcome.notified.containsKey("expire_7d"))
        assertTrue(outcome.notified.containsKey("expire_3d"))
        assertTrue(outcome.notified.containsKey("expire_1d"))
    }

    @Test
    fun `о конце подписки говорят один раз`() {
        val first = SubscriptionAlerts.evaluate(snapshot(expireAt = now - day), now)
        val second = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now - day, notified = first.notified),
            now + day,
        )

        assertEquals(emptyList<SubscriptionAlert>(), second.alerts)
    }

    @Test
    fun `продление обнуляет всю семью срока`() {
        val expired = SubscriptionAlerts.evaluate(snapshot(expireAt = now - day), now)

        val renewed = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 30 * day, notified = expired.notified),
            now,
        )

        assertEquals(emptyList<SubscriptionAlert>(), renewed.alerts)
        assertEquals(setOf("expire_base"), renewed.notified.keys)

        // И о новом сроке напомнят как о новом.
        val again = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 30 * day, notified = renewed.notified),
            now + 24 * day,
        )

        assertEquals(listOf(SubscriptionAlert.ExpiresIn(7)), again.alerts)
    }

    @Test
    fun `часы ушли назад — порог взводится обратно`() {
        val first = SubscriptionAlerts.evaluate(snapshot(expireAt = now + 5 * day), now)
        assertEquals(listOf(SubscriptionAlert.ExpiresIn(7)), first.alerts)

        // Человек поправил часы: до конца снова далеко.
        val back = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 5 * day, notified = first.notified),
            now - 20 * day,
        )
        assertEquals(emptyList<SubscriptionAlert>(), back.alerts)
        assertFalse(back.notified.containsKey("expire_7d"))

        // И когда срок подойдёт по-настоящему, напомнят снова.
        val later = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 5 * day, notified = back.notified),
            now,
        )
        assertEquals(listOf(SubscriptionAlert.ExpiresIn(7)), later.alerts)
    }

    @Test
    fun `подписка стала бессрочной — отметки срока выброшены`() {
        val first = SubscriptionAlerts.evaluate(snapshot(expireAt = now + 5 * day), now)

        val endless = SubscriptionAlerts.evaluate(
            snapshot(expireAt = 0, notified = first.notified),
            now,
        )

        assertEquals(emptyList<SubscriptionAlert>(), endless.alerts)
        assertTrue(endless.notified.isEmpty())
    }

    // --- трафик ---

    @Test
    fun `порог трафика срабатывает один раз`() {
        val first = SubscriptionAlerts.evaluate(snapshot(total = 100, used = 85), now)
        assertEquals(listOf(SubscriptionAlert.TrafficUsed(80)), first.alerts)

        val second = SubscriptionAlerts.evaluate(
            snapshot(total = 100, used = 86, notified = first.notified),
            now,
        )
        assertEquals(emptyList<SubscriptionAlert>(), second.alerts)
    }

    @Test
    fun `при скачке через два порога говорят про строгий`() {
        val outcome = SubscriptionAlerts.evaluate(snapshot(total = 100, used = 95), now)

        assertEquals(listOf(SubscriptionAlert.TrafficUsed(90)), outcome.alerts)
        // Мягкий порог помечен сказанным, чтобы не прилететь следом.
        assertTrue(outcome.notified.containsKey("traffic_80"))
    }

    @Test
    fun `пополнение трафика взводит пороги обратно`() {
        val used = SubscriptionAlerts.evaluate(snapshot(total = 100, used = 95), now)

        val refilled = SubscriptionAlerts.evaluate(
            snapshot(total = 100, used = 10, notified = used.notified),
            now,
        )
        assertEquals(emptyList<SubscriptionAlert>(), refilled.alerts)
        assertFalse(refilled.notified.containsKey("traffic_80"))

        val againstAgain = SubscriptionAlerts.evaluate(
            snapshot(total = 100, used = 81, notified = refilled.notified),
            now,
        )
        assertEquals(listOf(SubscriptionAlert.TrafficUsed(80)), againstAgain.alerts)
    }

    @Test
    fun `безлимитный трафик не считается`() {
        val outcome = SubscriptionAlerts.evaluate(snapshot(total = 0, used = 1L shl 40), now)

        assertEquals(emptyList<SubscriptionAlert>(), outcome.alerts)
    }

    @Test
    fun `отрицательный расход от панели не роняет счёт`() {
        val outcome = SubscriptionAlerts.evaluate(snapshot(total = 100, used = -5), now)

        assertEquals(emptyList<SubscriptionAlert>(), outcome.alerts)
    }

    @Test
    fun `терабайты считаются без переполнения`() {
        val terabyte = 1024L * 1024 * 1024 * 1024

        val outcome = SubscriptionAlerts.evaluate(
            snapshot(total = 100 * terabyte, used = 91 * terabyte),
            now,
        )

        assertEquals(listOf(SubscriptionAlert.TrafficUsed(90)), outcome.alerts)
    }

    @Test
    fun `перерасход сверх выданного — это сто процентов`() {
        val outcome = SubscriptionAlerts.evaluate(snapshot(total = 100, used = 250), now)

        assertEquals(listOf(SubscriptionAlert.TrafficUsed(100)), outcome.alerts)
    }

    // --- обе семьи разом ---

    @Test
    fun `срок и трафик — по одному уведомлению на каждую семью`() {
        val outcome = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 2 * day, total = 100, used = 95),
            now,
        )

        assertEquals(
            listOf(SubscriptionAlert.ExpiresIn(3), SubscriptionAlert.TrafficUsed(90)),
            outcome.alerts,
        )
    }

    // --- состояние ---

    @Test
    fun `панель сменила пороги — чужие отметки выброшены`() {
        val first = SubscriptionAlerts.evaluate(
            snapshot(expireAt = now + 5 * day, total = 100, used = 85),
            now,
        )
        assertTrue(first.notified.containsKey("traffic_80"))

        val changed = SubscriptionAlerts.evaluate(
            snapshot(
                expireAt = now + 5 * day,
                total = 100,
                used = 85,
                expireDays = listOf(7),
                trafficPercent = listOf(50),
                notified = first.notified,
            ),
            now,
        )

        assertFalse(changed.notified.containsKey("traffic_80"))
        assertFalse(changed.notified.containsKey("expire_3d"))
        assertTrue(changed.notified.containsKey("expire_7d"))
    }

    @Test
    fun `умолчания совпадают с теми, что рассылает панель`() {
        // Ядро (`native/config/panel.go`) при голом `notification-subs-expire`
        // подставляет тот же набор. Разъедутся — человек получит разные
        // напоминания от одной и той же панели на ПК и на телефоне.
        assertEquals(listOf(1, 3, 7), SubscriptionAlerts.DEFAULT_EXPIRE_DAYS)
        assertEquals(listOf(80, 90, 100), SubscriptionAlerts.DEFAULT_TRAFFIC_PERCENT)
    }
}
