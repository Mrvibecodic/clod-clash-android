package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class UpdateOutcomeTest {
    @Test
    fun `every combination of the two switches has one answer`() {
        assertEquals(
            UpdateOutcome.Plan(UpdateOutcome.Kind.Partial, error = true),
            UpdateOutcome.plan(partial = true, notifyErrors = true, notifyUpdates = true),
        )
        assertEquals(
            UpdateOutcome.Plan(UpdateOutcome.Kind.Partial, error = true),
            UpdateOutcome.plan(partial = true, notifyErrors = true, notifyUpdates = false),
        )
        assertEquals(
            UpdateOutcome.Plan(UpdateOutcome.Kind.Partial, error = false),
            UpdateOutcome.plan(partial = true, notifyErrors = false, notifyUpdates = true),
        )
        assertNull(UpdateOutcome.plan(partial = true, notifyErrors = false, notifyUpdates = false))
        assertEquals(
            UpdateOutcome.Plan(UpdateOutcome.Kind.Success, error = false),
            UpdateOutcome.plan(partial = false, notifyErrors = true, notifyUpdates = true),
        )
        assertNull(UpdateOutcome.plan(partial = false, notifyErrors = true, notifyUpdates = false))
        assertEquals(
            UpdateOutcome.Plan(UpdateOutcome.Kind.Success, error = false),
            UpdateOutcome.plan(partial = false, notifyErrors = false, notifyUpdates = true),
        )
        assertNull(UpdateOutcome.plan(partial = false, notifyErrors = false, notifyUpdates = false))
    }

    @Test
    fun `a partial update does not reuse the identifier of the other outcomes`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

        assertEquals(
            UpdateOutcome.id(uuid, UpdateOutcome.Kind.Success),
            UpdateOutcome.id(uuid, UpdateOutcome.Kind.Failure),
        )

        assertNotEquals(
            UpdateOutcome.id(uuid, UpdateOutcome.Kind.Success),
            UpdateOutcome.id(uuid, UpdateOutcome.Kind.Partial),
        )
    }

    @Test
    fun `posting one outcome replaces the identifiers of the others`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

        for (kind in UpdateOutcome.Kind.entries) {
            val replaced = UpdateOutcome.replaced(uuid, kind)

            assertEquals(false, replaced.contains(UpdateOutcome.id(uuid, kind)))

            for (other in UpdateOutcome.Kind.entries.filter { it != kind }) {
                val id = UpdateOutcome.id(uuid, other)

                if (id != UpdateOutcome.id(uuid, kind)) {
                    assertEquals(true, replaced.contains(id))
                }
            }
        }
    }

    @Test
    fun `a success without its own notice still has every outcome identifier to clear`() {
        val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000ff")

        val ids = UpdateOutcome.ids(uuid)

        for (kind in UpdateOutcome.Kind.entries) {
            assertTrue(ids.contains(UpdateOutcome.id(uuid, kind)))
        }

        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `only a scheduled update waits for the network to settle`() {
        assertFalse(UpdateOutcome.waitsForNetwork(ProfileUpdateWorker.Kind.Manual))
        assertTrue(UpdateOutcome.waitsForNetwork(ProfileUpdateWorker.Kind.Periodic))
        assertTrue(UpdateOutcome.waitsForNetwork(ProfileUpdateWorker.Kind.Expiry))
    }

    @Test
    fun `a manual failure is always announced`() {
        for (attempt in 0..2) for (temporary in listOf(true, false)) for (retrying in listOf(true, false)) {
            assertTrue(UpdateOutcome.notifiesFailure(ProfileUpdateWorker.Kind.Manual, attempt, temporary, retrying))
        }
    }

    @Test
    fun `a periodic network failure with a retry ahead waits for the retry`() {
        val periodic = ProfileUpdateWorker.Kind.Periodic

        assertFalse(UpdateOutcome.notifiesFailure(periodic, attempt = 0, temporary = true, retrying = true))
        assertTrue(UpdateOutcome.notifiesFailure(periodic, attempt = 1, temporary = true, retrying = true))
        assertTrue(UpdateOutcome.notifiesFailure(periodic, attempt = 0, temporary = true, retrying = false))
        assertTrue(UpdateOutcome.notifiesFailure(periodic, attempt = 0, temporary = false, retrying = true))
        assertTrue(UpdateOutcome.notifiesFailure(periodic, attempt = 3, temporary = false, retrying = false))
    }

    @Test
    fun `an expiry series is announced once, on its first attempt`() {
        val expiry = ProfileUpdateWorker.Kind.Expiry

        assertTrue(UpdateOutcome.notifiesFailure(expiry, attempt = 0, temporary = true, retrying = true))
        assertTrue(UpdateOutcome.notifiesFailure(expiry, attempt = 0, temporary = false, retrying = true))
        assertFalse(UpdateOutcome.notifiesFailure(expiry, attempt = 1, temporary = true, retrying = true))
        assertFalse(UpdateOutcome.notifiesFailure(expiry, attempt = 1, temporary = false, retrying = true))
    }

    @Test
    fun `only a quick network failure of a scheduled update is tried again at once`() {
        val quick = UpdateOutcome.QUICK_FAILURE - 1

        assertTrue(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Periodic, temporary = true, spent = 0, others = 0))
        assertTrue(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Expiry, temporary = true, spent = quick, others = 0))
        assertFalse(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Manual, temporary = true, spent = 0, others = 0))
        assertFalse(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Periodic, temporary = false, spent = 0, others = 0))
        // Попытка, съевшая бюджет (таймаут), с повтором вышла бы за предел задачи
        assertFalse(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Periodic, temporary = true, spent = UpdateOutcome.QUICK_FAILURE, others = 0))
        assertFalse(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Periodic, temporary = true, spent = TimeUnit.SECONDS.toMillis(180), others = 0))
        // Чужая загрузка впереди в очереди — длина неизвестна, повтор подождёт бэкоффа
        assertFalse(UpdateOutcome.retriesAtOnce(ProfileUpdateWorker.Kind.Periodic, temporary = true, spent = 0, others = 1))
    }

    @Test
    fun `different profiles never share an identifier`() {
        val first = UUID.fromString("0f7c3a52-1111-4222-8333-444455556666")
        val second = UUID.fromString("1a2b3c4d-5e6f-4711-8922-abcdefabcdef")

        assertNotEquals(
            UpdateOutcome.id(first, UpdateOutcome.Kind.Partial),
            UpdateOutcome.id(second, UpdateOutcome.Kind.Partial),
        )
    }
}
