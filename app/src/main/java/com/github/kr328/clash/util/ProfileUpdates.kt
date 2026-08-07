package com.github.kr328.clash.util

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Какие подписки обновляются прямо сейчас.
 *
 * Живёт на весь процесс, а не в активити, по двум причинам. Первая: обновление
 * выполняет служебный процесс, а `IProfileManager.update` только ставит ему
 * задачу и возвращается сразу — то есть «идёт» переживает и поворот экрана,
 * и уход из приложения. Вторая: у главного экрана `configChanges` только
 * `uiMode`, поэтому поворот пересоздаёт активити вместе со всеми её полями,
 * и набор в поле активити обнулялся бы ровно посреди работы.
 *
 * Вместо голого множества — сроки: у каждой отметки есть момент, после которого
 * она считается протухшей. Отметку снимает сообщение о завершении
 * (ACTION_PROFILE_UPDATE_COMPLETED/FAILED), но оно может и не дойти — экран
 * снимает наблюдателя на onStop, а служебный процесс может умереть, не сказав
 * ничего. Без срока значок в таком случае крутился бы до перезапуска экрана,
 * а кнопка осталась бы заблокированной.
 */
object ProfileUpdates {
    /**
     * Полторы минуты — заведомо больше, чем занимает скачивание файла подписки
     * даже на плохой сети, и достаточно мало, чтобы человек не решил, что
     * приложение зависло.
     */
    private val TIMEOUT = TimeUnit.SECONDS.toMillis(90)

    private val deadlines = MutableStateFlow<Map<UUID, Long>>(emptyMap())

    /** Сроки отметок; экрану нужны только ключи. */
    val running: StateFlow<Map<UUID, Long>> = deadlines

    /**
     * Отметить подписки обновляющимися. Весь список разом: помечать по одной
     * внутри цикла нельзя — быстрая подписка успевает прислать «готово»
     * раньше, чем очередь дойдёт до следующей, набор на миг пустеет, и
     * значок в шапке успевает дёрнуться.
     */
    fun start(uuids: Collection<UUID>) {
        if (uuids.isEmpty()) return

        val until = SystemClock.elapsedRealtime() + TIMEOUT

        deadlines.update { current -> current.alive() + uuids.associateWith { until } }
    }

    fun finish(uuid: UUID) {
        deadlines.update { (it - uuid).alive() }
    }

    /** Снять протухшие отметки. Зовётся с общего секундного тика главного экрана. */
    fun prune() {
        if (deadlines.value.isEmpty()) return

        deadlines.update { it.alive() }
    }

    private fun Map<UUID, Long>.alive(): Map<UUID, Long> {
        val now = SystemClock.elapsedRealtime()

        return if (all { it.value > now }) this else filterValues { it > now }
    }
}
