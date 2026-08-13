package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Таймер сессии врёт заметнее всего тогда, когда системные часы уехали:
 * сдвиг на трое суток превращает секунды после подключения в «73 часа».
 * Здесь проверяется, что монотонные часы этот сдвиг не пускают на экран.
 */
class SessionClockTest {
    private val hour = TimeUnit.HOURS.toMillis(1)

    @Test
    fun `считаем по монотонным часам`() {
        val seconds = SessionClock.seconds(
            startedAt = 1_000_000,
            startedElapsed = 60_000,
            nowWall = 1_125_000,
            nowElapsed = 185_000,
        )

        assertEquals(125, seconds)
    }

    @Test
    fun `скачок системных часов на трое суток таймер не трогает`() {
        val seconds = SessionClock.seconds(
            startedAt = 1_000_000,
            // Синхронизация времени увела системные часы на 73 часа вперёд,
            // монотонные при этом отсчитали ровно две секунды.
            startedElapsed = 60_000,
            nowWall = 1_000_000 + 73 * hour + 2_000,
            nowElapsed = 62_000,
        )

        assertEquals(2, seconds)
    }

    @Test
    fun `в первый же тик после подключения ноль, а не отрицательное`() {
        val seconds = SessionClock.seconds(
            startedAt = 1_000_000,
            startedElapsed = 60_000,
            nowWall = 1_000_000,
            nowElapsed = 60_000,
        )

        assertEquals(0, seconds)
    }

    @Test
    fun `без монотонной метки считаем по системным часам`() {
        val seconds = SessionClock.seconds(
            startedAt = 1_000_000,
            startedElapsed = 0,
            nowWall = 1_030_000,
            nowElapsed = 500_000,
        )

        assertEquals(30, seconds)
    }

    @Test
    fun `метка от прошлой загрузки телефона в расчёт не идёт`() {
        val seconds = SessionClock.seconds(
            startedAt = 1_000_000,
            // После перезагрузки счётчик пошёл заново и оказался меньше метки.
            startedElapsed = 900_000,
            nowWall = 1_045_000,
            nowElapsed = 30_000,
        )

        assertEquals(45, seconds)
    }

    @Test
    fun `часы, переведённые назад, дают ноль, а не отрицательное время`() {
        val seconds = SessionClock.seconds(
            startedAt = 1_000_000,
            startedElapsed = 0,
            nowWall = 900_000,
            nowElapsed = 30_000,
        )

        assertEquals(0, seconds)
    }

    @Test
    fun `без меток вовсе таймер нулевой`() {
        assertEquals(
            0,
            SessionClock.seconds(
                startedAt = 0,
                startedElapsed = 0,
                nowWall = 1_000_000,
                nowElapsed = 30_000,
            ),
        )
    }
}
