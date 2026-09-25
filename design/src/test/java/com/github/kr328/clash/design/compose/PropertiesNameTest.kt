package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.screen.nameField
import com.github.kr328.clash.design.compose.screen.withNameField
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PropertiesNameTest {
    private fun profile(name: String, nameManual: Boolean) = Profile(
        uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        name = name,
        type = Profile.Type.Url,
        source = "https://example.org/sub",
        active = true,
        interval = 0,
        upload = 0,
        download = 0,
        total = 0,
        expire = 0,
        updatedAt = 0,
        imported = true,
        pending = false,
        nameManual = nameManual,
    )

    @Test
    fun `в поле только своё имя`() {
        assertEquals("Работа", profile("Работа", true).nameField)
        assertEquals("", profile("Новая подписка", false).nameField)
    }

    @Test
    fun `введённое имя становится своим`() {
        val edited = profile("Новая подписка", false).withNameField(" Работа ")

        assertEquals("Работа", edited.name)
        assertTrue(edited.nameManual)
    }

    @Test
    fun `пустое поле — своего имени нет, сохранённое остаётся запасным`() {
        val cleared = profile("Работа", true).withNameField("  ")

        assertEquals("Работа", cleared.name)
        assertFalse(cleared.nameManual)
    }

    @Test
    fun `нетронутая форма не отличается от сохранённого`() {
        for (stored in listOf(profile("Работа", true), profile("Новая подписка", false))) {
            assertEquals(stored, stored.withNameField(stored.nameField))
        }
    }
}
