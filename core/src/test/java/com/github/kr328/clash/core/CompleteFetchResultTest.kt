package com.github.kr328.clash.core

import com.github.kr328.clash.core.bridge.ClashException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompleteFetchResultTest {
    private class Broken(private val delegate: CompletableDeferred<Unit>) :
        CompletableDeferred<Unit> by delegate {
        override fun complete(value: Unit): Boolean = throw OutOfMemoryError("no room")
    }

    @Test
    fun successCompletesWithUnit() {
        val deferred = CompletableDeferred<Unit>()

        deferred.completeFetchResult(null)

        assertTrue(deferred.isCompleted)
        assertEquals(Unit, deferred.getCompleted())
    }

    @Test
    fun errorCompletesExceptionally() {
        val deferred = CompletableDeferred<Unit>()

        deferred.completeFetchResult("bad subscription")

        assertTrue(deferred.isCompleted)

        val cause = deferred.getCompletionExceptionOrNull()

        assertTrue("$cause", cause is ClashException)
        assertEquals("bad subscription", cause?.message)
    }

    @Test
    fun emptyErrorStillCompletesExceptionally() {
        val deferred = CompletableDeferred<Unit>()

        deferred.completeFetchResult("")

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.getCompletionExceptionOrNull() is ClashException)
    }

    @Test
    fun hugeErrorStillCompletesExceptionally() {
        val deferred = CompletableDeferred<Unit>()

        deferred.completeFetchResult("x".repeat(1024 * 1024))

        assertTrue(deferred.isCompleted)
        assertTrue(deferred.getCompletionExceptionOrNull() is ClashException)
    }

    @Test
    fun failedCompletionFallsBackToException() {
        val delegate = CompletableDeferred<Unit>()

        Broken(delegate).completeFetchResult(null)

        assertTrue(delegate.isCompleted)
        assertTrue(delegate.getCompletionExceptionOrNull() is ClashException)
    }

    @Test
    fun secondCompletionIsHarmless() {
        val deferred = CompletableDeferred<Unit>()

        deferred.completeFetchResult(null)
        deferred.completeFetchResult("late failure")

        assertTrue(deferred.isCompleted)
        assertEquals(Unit, deferred.getCompleted())
    }
}
