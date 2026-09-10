package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionSeriesTest {
    private val window = 60_000L

    @Test
    fun `одна отметка внутри лимита`() {
        val series = ReactionSeries(window, 3)

        val mark = series.mark(1_000L)

        assertEquals(1, mark.count)
        assertTrue(mark.withinLimit)
    }

    @Test
    fun `третья отметка при лимите три выходит за лимит`() {
        val series = ReactionSeries(window, 3)

        val marks = listOf(0L, 5_000L, 10_000L).map { series.mark(it) }

        assertEquals(listOf(1, 2, 3), marks.map { it.count })
        assertEquals(listOf(true, true, false), marks.map { it.withinLimit })
    }

    @Test
    fun `пятая отметка при лимите пять выходит за лимит`() {
        val series = ReactionSeries(window, 5)

        val marks = (0 until 5).map { series.mark(it * 5_000L) }

        assertEquals(listOf(1, 2, 3, 4, 5), marks.map { it.count })
        assertEquals(listOf(true, true, true, true, false), marks.map { it.withinLimit })
    }

    @Test
    fun `обнуление начинает серию заново`() {
        val series = ReactionSeries(window, 3)

        series.mark(0L)
        series.mark(1_000L)

        series.clear()

        val third = series.mark(2_000L)
        val fourth = series.mark(3_000L)

        assertEquals(1, third.count)
        assertEquals(2, fourth.count)
        assertTrue(third.withinLimit)
        assertTrue(fourth.withinLimit)
    }

    @Test
    fun `отметка ровно на границе окна выбрасывается`() {
        val series = ReactionSeries(window, 3)

        series.mark(0L)

        assertEquals(1, series.mark(window).count)
    }

    @Test
    fun `отметка на миллисекунду младше границы остаётся`() {
        val series = ReactionSeries(window, 3)

        series.mark(0L)

        assertEquals(2, series.mark(window - 1).count)
    }

    @Test
    fun `обнуление пустой серии не мешает следующей отметке`() {
        val series = ReactionSeries(window, 3)

        series.clear()

        assertEquals(1, series.mark(0L).count)
    }

    @Test
    fun `подтверждение сети держит разрыв соединений включённым, пока сеть не флапает сверх лимита`() {
        val unconfirmed = ReactionSeries(window, 3)
        val flaps = ReactionSeries(window, 5)

        val resets = ArrayList<Boolean>()
        val holds = ArrayList<Boolean>()

        for (i in 0 until 6) {
            val now = i * 5_000L

            val unconfirmedMark = unconfirmed.mark(now)
            val flapMark = flaps.mark(now)

            resets += resetsConnections(true, unconfirmedMark, flapMark)
            holds += flapMark.withinLimit

            unconfirmed.clear()
        }

        assertEquals(listOf(true, true, true, true, false, false), resets)
        assertEquals(listOf(true, true, true, true, false, false), holds)
    }

    @Test
    fun `за минуту разрыв соединений разрешён не больше четырёх раз`() {
        val unconfirmed = ReactionSeries(window, 3)
        val flaps = ReactionSeries(window, 5)

        var allowed = 0

        for (i in 0 until 12) {
            val now = i * 5_000L

            if (resetsConnections(true, unconfirmed.mark(now), flaps.mark(now))) {
                allowed++
            }

            unconfirmed.clear()
        }

        assertEquals(4, allowed)
    }

    @Test
    fun `выключатель пользователя гасит разрыв соединений при любых счётчиках`() {
        val unconfirmed = ReactionSeries(window, 3)
        val flaps = ReactionSeries(window, 5)

        assertFalse(resetsConnections(false, unconfirmed.mark(0L), flaps.mark(0L)))
    }

    @Test
    fun `без подтверждения сети оба выключателя гаснут на своих лимитах`() {
        val unconfirmed = ReactionSeries(window, 3)
        val flaps = ReactionSeries(window, 5)

        val resets = ArrayList<Boolean>()
        val holds = ArrayList<Boolean>()

        for (i in 0 until 6) {
            val now = i * 5_000L

            val unconfirmedMark = unconfirmed.mark(now)
            val flapMark = flaps.mark(now)

            resets += resetsConnections(true, unconfirmedMark, flapMark)
            holds += flapMark.withinLimit
        }

        assertEquals(listOf(true, true, false, false, false, false), resets)
        assertEquals(listOf(true, true, true, true, false, false), holds)
    }

    @Test
    fun `счётчик флапа никогда не меньше счётчика без подтверждений`() {
        val unconfirmed = ReactionSeries(window, 3)
        val flaps = ReactionSeries(window, 5)

        for (i in 0 until 12) {
            val now = i * 5_000L

            val unconfirmedMark = unconfirmed.mark(now)
            val flapMark = flaps.mark(now)

            assertTrue(flapMark.count >= unconfirmedMark.count)
            assertFalse(flapMark.count == 1 && unconfirmedMark.count != 1)

            if (i % 3 == 2) {
                unconfirmed.clear()
            }
        }
    }
}
