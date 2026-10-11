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
                launch { runModule("Broken", { name, e, live -> reported = Triple(name, e, live); events += "reported" }) { throw failure } }

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

        val job = launch(start = CoroutineStart.UNDISPATCHED) { runModule("Idle", { _, _, _ -> reported = true }) { awaitCancellation() } }

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
                runModule("Closing", { _, _, l -> live = l }) {
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
}
