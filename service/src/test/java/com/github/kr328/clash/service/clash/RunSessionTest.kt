package com.github.kr328.clash.service.clash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class RunSessionTest {
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // Модуль, который после отмены ещё пишет: подписка загрузилась ровно на стопе
    private fun CoroutineScope.lateWriter(name: String) = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                delay(20)

                events += "$name late write"
            }
        }
    }

    private suspend fun session(prepare: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit) = runSession(
        prepare = prepare,
        modules = { this },
        block = block,
        afterModules = { events += "stopped" },
        teardown = { events += "core reset" },
    )

    @Test
    fun `остановка объявляется после поздней записи модуля`() = runBlocking {
        // Обычный стоп выходит отменой рантайма, как и до правки
        try {
            session {
                lateWriter("config")

                try {
                    delay(10)
                } finally {
                    events += "begin stop"
                }
            }
        } catch (e: CancellationException) {
            // ожидаемо
        }

        assertEquals(listOf("begin stop", "config late write", "stopped", "core reset"), events)
    }

    @Test
    fun `отказ модуля без бюджета — остановка после всех модулей и один раз`() = runBlocking {
        val failure = IllegalStateException("module failed")

        session {
            lateWriter("config")

            launch { runModule("Broken", null, { _, _, _, _ -> false }) { delay(10); throw failure } }

            awaitCancellation()
        }

        assertEquals(listOf("config late write", "stopped", "core reset"), events)
    }

    @Test
    fun `внешняя отмена — остановка после модулей`() = runBlocking {
        val job = launch {
            session {
                lateWriter("config")

                awaitCancellation()
            }
        }

        delay(10)

        job.cancel()
        job.join()

        assertEquals(listOf("config late write", "stopped", "core reset"), events)
    }

    @Test
    fun `сбой подготовки ядра — остановка всё равно объявляется`() = runBlocking {
        val failure = IllegalStateException("reset failed")

        try {
            session(prepare = { throw failure }) { fail("modules must not start") }

            fail("the failure must leave the runtime")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }

        assertEquals(listOf("stopped", "core reset"), events)
    }

    @Test
    fun `сбой объявления остановки не отменяет сброс ядра`() = runBlocking {
        val failure = IllegalStateException("broadcast failed")

        try {
            runSession(
                prepare = {},
                modules = { this },
                block = {},
                afterModules = { throw failure },
                teardown = { events += "core reset" },
            )

            fail("the failure must leave the runtime")
        } catch (e: IllegalStateException) {
            // Отладочный режим корутин подменяет исключение копией
            assertEquals(failure.message, e.message)
        }

        assertEquals(listOf("core reset"), events)
    }
}
