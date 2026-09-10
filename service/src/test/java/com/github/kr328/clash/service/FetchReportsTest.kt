package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.FetchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class FetchReportsTest {
    private fun status(action: FetchStatus.Action, vararg args: String) =
        FetchStatus(action, args.toList(), 0, 0)

    @Test
    fun `subscription info is kept and not forwarded`() {
        val reports = FetchReports(null)

        assertEquals(false, reports.record(status(FetchStatus.Action.SubscriptionInfo)))
        assertNotNull(reports.info())
    }

    @Test
    fun `progress is forwarded`() {
        val reports = FetchReports(null)

        assertEquals(true, reports.record(status(FetchStatus.Action.FetchProviders, "alpha")))
        assertEquals(true, reports.record(status(FetchStatus.Action.Verifying)))
        assertNull(reports.info())
    }

    @Test
    fun `a failure is both collected and forwarded`() {
        val reports = FetchReports(null)

        assertEquals(true, reports.record(status(FetchStatus.Action.ProviderFailed, "alpha", "boom")))
        assertEquals(listOf("alpha"), reports.failed())
    }

    @Test
    fun `a dropped observer stays dropped`() {
        val reports = FetchReports(IFetchObserverStub())

        assertNotNull(reports.observer())

        reports.drop()

        assertNull(reports.observer())
    }

    @Test
    fun `four reporters never lose a name and never duplicate one`() {
        val names = (0 until 64).map { "provider-$it" }
        val reports = FetchReports(IFetchObserverStub())
        val forwarded = AtomicInteger()
        val start = CountDownLatch(1)

        val threads = (0 until 4).map { slot ->
            Thread {
                start.await()

                for (name in names) {
                    if (reports.record(status(FetchStatus.Action.ProviderFailed, name))) {
                        forwarded.incrementAndGet()
                    }

                    reports.record(status(FetchStatus.Action.FetchProviders, name))
                    reports.record(status(FetchStatus.Action.SubscriptionInfo))
                }
            }.apply { start() }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertEquals(names, reports.failed())
        assertEquals(4 * names.size, forwarded.get())
        assertNotNull(reports.info())
    }

    private class IFetchObserverStub : com.github.kr328.clash.service.remote.IFetchObserver {
        override fun updateStatus(status: FetchStatus) = Unit
    }
}
