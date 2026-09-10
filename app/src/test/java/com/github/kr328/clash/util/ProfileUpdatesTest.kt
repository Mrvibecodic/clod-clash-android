package com.github.kr328.clash.util

import com.github.kr328.clash.remote.UpdatingProfiles
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class ProfileUpdatesTest {
    private val first = UUID.fromString("0f7c3a52-1111-4222-8333-444455556666")
    private val second = UUID.fromString("1a2b3c4d-5e6f-4711-8922-abcdefabcdef")

    private val start = TimeUnit.HOURS.toMillis(5)

    @Before
    fun reset() {
        ProfileUpdates.prune(Long.MAX_VALUE / 2)

        assertEquals(emptySet<UUID>(), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `the spinner ceiling is twice the worst case of one update`() {
        assertEquals(TimeUnit.SECONDS.toMillis(300), ProfileUpdates.WORST_CASE)
        assertEquals(ProfileUpdates.WORST_CASE * 2, ProfileUpdates.TIMEOUT)
    }

    @Test
    fun `a confirmed update keeps spinning`() {
        ProfileUpdates.start(listOf(first), start)

        ProfileUpdates.reconcile(UpdatingProfiles.Known(setOf(first)), start + TimeUnit.MINUTES.toMillis(4))

        assertEquals(setOf(first), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `an update the background process does not report is dropped`() {
        ProfileUpdates.start(listOf(first), start)

        ProfileUpdates.reconcile(UpdatingProfiles.Known(emptySet()), start + TimeUnit.MINUTES.toMillis(4))

        assertEquals(emptySet<UUID>(), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `a fresh start survives the very first reconcile`() {
        ProfileUpdates.start(listOf(first), start)

        ProfileUpdates.reconcile(UpdatingProfiles.Known(emptySet()), start + TimeUnit.SECONDS.toMillis(5))

        assertEquals(setOf(first), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `an unavailable provider keeps the spinner for a minute and a half`() {
        ProfileUpdates.start(listOf(first), start)

        ProfileUpdates.reconcile(UpdatingProfiles.Unavailable, start + TimeUnit.SECONDS.toMillis(10))

        assertEquals(setOf(first), ProfileUpdates.running.value.keys)

        ProfileUpdates.reconcile(UpdatingProfiles.Unavailable, start + TimeUnit.SECONDS.toMillis(89))

        assertEquals(setOf(first), ProfileUpdates.running.value.keys)

        ProfileUpdates.reconcile(UpdatingProfiles.Unavailable, start + TimeUnit.SECONDS.toMillis(91))

        assertEquals(emptySet<UUID>(), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `the unavailable ceiling counts from the last confirmation`() {
        ProfileUpdates.start(listOf(first), start)

        val confirmed = start + TimeUnit.MINUTES.toMillis(4)

        ProfileUpdates.reconcile(UpdatingProfiles.Known(setOf(first)), confirmed)
        ProfileUpdates.reconcile(UpdatingProfiles.Unavailable, confirmed + TimeUnit.SECONDS.toMillis(89))

        assertEquals(setOf(first), ProfileUpdates.running.value.keys)

        ProfileUpdates.reconcile(UpdatingProfiles.Unavailable, confirmed + TimeUnit.SECONDS.toMillis(91))

        assertEquals(emptySet<UUID>(), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `an update reported by the background process alone starts spinning`() {
        ProfileUpdates.reconcile(UpdatingProfiles.Known(setOf(second)), start)

        assertEquals(setOf(second), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `the overall ceiling still drops a forgotten update`() {
        ProfileUpdates.start(listOf(first), start)

        ProfileUpdates.prune(start + ProfileUpdates.TIMEOUT + 1)

        assertEquals(emptySet<UUID>(), ProfileUpdates.running.value.keys)
    }

    @Test
    fun `a clock that went backwards does not drop a running update`() {
        ProfileUpdates.start(listOf(first), start)

        ProfileUpdates.reconcile(UpdatingProfiles.Unavailable, start - TimeUnit.HOURS.toMillis(1))

        assertEquals(setOf(first), ProfileUpdates.running.value.keys)
    }
}
