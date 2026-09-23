package com.github.kr328.clash

import com.github.kr328.clash.core.model.ConfigurationOverride
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOverrideTest {
    private fun configuration() = ConfigurationOverride(
        mixedPort = 7890,
        hosts = linkedMapOf("a.test" to "1.1.1.1", "b.test" to "2.2.2.2"),
    ).apply { dns.nameServer = listOf("1.1.1.1") }

    @Test
    fun `нетронутый черновик чист`() {
        assertFalse(PendingOverride.Draft(configuration()).dirty())
    }

    @Test
    fun `правка поля делает черновик грязным`() {
        val draft = PendingOverride.Draft(configuration())

        draft.value.allowLan = true

        assertTrue(draft.dirty())
    }

    @Test
    fun `правка вложенного поля делает черновик грязным`() {
        val draft = PendingOverride.Draft(configuration())

        draft.value.dns.enable = true

        assertTrue(draft.dirty())
    }

    @Test
    fun `пересобранная в том же порядке карта хостов не считается правкой`() {
        val draft = PendingOverride.Draft(configuration())

        draft.value.hosts = draft.value.hosts!!.entries.map { it.key to it.value }.toMap()

        assertFalse(draft.dirty())
    }

    @Test
    fun `возврат значения к исходному снимает грязь`() {
        val draft = PendingOverride.Draft(configuration())

        draft.value.mixedPort = 1
        draft.value.mixedPort = 7890

        assertFalse(draft.dirty())
    }
}
