package com.github.kr328.clash.service.clash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunModuleTest {
    @Test
    fun failureIsReportedBeforeTheRuntimeSeesCancellation() = runBlocking {
        val failure = IllegalStateException("module failed")
        val events = mutableListOf<String>()
        var reported: Triple<String, Exception, Boolean>? = null

        try {
            coroutineScope {
                launch { runModule("Broken", null, { name, e, live, _ -> reported = Triple(name, e, live); events += "reported"; false }) { throw failure } }

                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    events += "body cancelled"
                }
            }

            fail("the failure must leave the runtime")
        } catch (e: CancellationException) {
            fail("the failure must not look like a cancellation")
        } catch (e: Exception) {
            assertSame(failure, e.cause)
        }

        assertEquals("Broken", reported?.first)
        assertSame(failure, reported?.second)
        assertEquals(true, reported?.third)
        assertEquals(listOf("reported", "body cancelled"), events)
    }

    @Test
    fun cancellationIsNotAFailure() = runBlocking {
        var reported = false

        val job = launch(start = CoroutineStart.UNDISPATCHED) { runModule("Idle", null, { _, _, _, _ -> reported = true; false }) { awaitCancellation() } }

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(false, reported)
    }

    // Модуль, упавший уже отменённым (сворачивание сессии), сообщает это как
    // следствие остановки, а не как её причину
    @Test
    fun failureWhileCancelledIsNotLive() = runBlocking {
        var live: Boolean? = null

        supervisorScope {
            val job = launch(CoroutineExceptionHandler { _, _ -> }, CoroutineStart.UNDISPATCHED) {
                runModule("Closing", restartBudget(), { _, _, l, _ -> live = l; false }) {
                    try {
                        awaitCancellation()
                    } finally {
                        throw IllegalStateException("cleanup failed")
                    }
                }
            }

            job.cancel()
            job.join()
        }

        assertEquals(false, live)
    }

    // Упавший модуль запускается заново, пока хватает бюджета; дальше отказ
    // гасит рантайм, как у неперезапускаемого
    @Test
    fun failedModuleRestartsWithinBudget() = runBlocking {
        var runs = 0
        val restarts = mutableListOf<Boolean>()
        val budget = RestartBudget(2, 1_000L) { 0L }

        try {
            runModule("Flaky", budget, { _, _, _, tryRestart -> tryRestart().also { restarts += it } }, restartDelayMs = 0) {
                runs++

                throw IllegalStateException("failed run $runs")
            }

            fail("the failure past the budget must leave the runtime")
        } catch (e: CancellationException) {
            fail("the failure must not look like a cancellation")
        } catch (e: Exception) {
            assertEquals("failed run 3", e.cause?.message)
        }

        assertEquals(3, runs)
        assertEquals(listOf(true, true, false), restarts)
    }

    @Test
    fun restartedModuleThatRecoversKeepsRunning() = runBlocking {
        var runs = 0

        runModule("Recovering", RestartBudget(2, 1_000L) { 0L }, { _, _, _, tryRestart -> tryRestart() }, restartDelayMs = 0) {
            if (++runs == 1) throw IllegalStateException("first run failed")
        }

        assertEquals(2, runs)
    }

    @Test
    fun budgetFreesUpAfterTheWindow() {
        var now = 0L
        val budget = RestartBudget(2, 1_000L) { now }

        assertTrue(budget.take())
        assertTrue(budget.take())
        assertEquals(false, budget.take())

        now = 999L
        assertEquals(false, budget.take())

        now = 1_000L
        assertTrue(budget.take())
        assertTrue(budget.take())
        assertEquals(false, budget.take())
    }
}
