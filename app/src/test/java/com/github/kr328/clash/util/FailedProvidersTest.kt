package com.github.kr328.clash.util

import com.github.kr328.clash.core.model.FetchStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class FailedProvidersTest {
    private fun status(action: FetchStatus.Action, vararg args: String) =
        FetchStatus(action, args.toList(), 0, 0)

    @Test
    fun `a non-failure status collects nothing`() {
        assertEquals(
            emptyList<String>(),
            FailedProviders.accumulate(emptyList(), status(FetchStatus.Action.FetchProviders, "alpha")),
        )
    }

    @Test
    fun `only failures are collected`() {
        var collected = emptyList<String>()

        for (
        reported in listOf(
            status(FetchStatus.Action.FetchConfiguration, "host"),
            status(FetchStatus.Action.FetchProviders, "alpha"),
            status(FetchStatus.Action.ProviderFailed, "alpha", "boom"),
            status(FetchStatus.Action.SubscriptionInfo),
            status(FetchStatus.Action.ProviderFailed, "beta", "boom"),
            status(FetchStatus.Action.Verifying),
        )
        ) {
            collected = FailedProviders.accumulate(collected, reported)
        }

        assertEquals(listOf("alpha", "beta"), collected)
    }

    @Test
    fun `the same provider is never listed twice`() {
        var collected = emptyList<String>()

        collected = FailedProviders.accumulate(collected, status(FetchStatus.Action.ProviderFailed, "alpha"))
        collected = FailedProviders.accumulate(collected, status(FetchStatus.Action.ProviderFailed, "alpha"))

        assertEquals(listOf("alpha"), collected)
    }

    @Test
    fun `a failure without a name is ignored`() {
        assertEquals(
            emptyList<String>(),
            FailedProviders.accumulate(emptyList(), status(FetchStatus.Action.ProviderFailed)),
        )
    }

    @Test
    fun `a blank name is ignored`() {
        assertEquals(
            emptyList<String>(),
            FailedProviders.accumulate(emptyList(), status(FetchStatus.Action.ProviderFailed, "   ")),
        )
    }

    @Test
    fun `order of first appearance is kept`() {
        var collected = emptyList<String>()

        for (name in listOf("beta", "alpha", "beta", "gamma")) {
            collected = FailedProviders.accumulate(collected, status(FetchStatus.Action.ProviderFailed, name))
        }

        assertEquals(listOf("beta", "alpha", "gamma"), collected)
    }

    @Test
    fun `the holder collects failures from concurrent reports`() {
        val collected = AtomicReference(emptyList<String>())

        val threads = (1..8).map { index ->
            Thread {
                repeat(50) {
                    FailedProviders.accumulate(
                        collected,
                        status(FetchStatus.Action.ProviderFailed, "provider$index"),
                    )
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals((1..8).map { "provider$it" }.toSet(), collected.get().toSet())
    }

    @Test
    fun `the holder is untouched by a non-failure status`() {
        val collected = AtomicReference(listOf("alpha"))

        FailedProviders.accumulate(collected, status(FetchStatus.Action.Verifying))

        assertEquals(listOf("alpha"), collected.get())
    }
}
