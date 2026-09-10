package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

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
    fun `different profiles never share an identifier`() {
        val first = UUID.fromString("0f7c3a52-1111-4222-8333-444455556666")
        val second = UUID.fromString("1a2b3c4d-5e6f-4711-8922-abcdefabcdef")

        assertNotEquals(
            UpdateOutcome.id(first, UpdateOutcome.Kind.Partial),
            UpdateOutcome.id(second, UpdateOutcome.Kind.Partial),
        )
    }
}
