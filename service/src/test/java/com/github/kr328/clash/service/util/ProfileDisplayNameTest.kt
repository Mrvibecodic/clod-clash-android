package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.model.PanelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDisplayNameTest {
    @Test
    fun `без своего имени показывается название панели, без него — сохранённое`() {
        assertEquals("a", profileDisplayName(null, "a", false))
        assertEquals("a", profileDisplayName(PanelInfo(title = " "), "a", false))
        assertEquals("T", profileDisplayName(PanelInfo(title = "T"), "a", false))
    }

    @Test
    fun `своё имя первым, название панели в скобках`() {
        assertEquals("Работа (T)", profileDisplayName(PanelInfo(title = "T"), "Работа", true))
    }

    @Test
    fun `совпадающие имена показываются одним`() {
        assertEquals("T", profileDisplayName(PanelInfo(title = "T"), "T", true))
    }

    @Test
    fun `своё имя без названия панели показывается как есть`() {
        assertEquals("Работа", profileDisplayName(null, "Работа", true))
        assertEquals("Работа", profileDisplayName(PanelInfo(title = " "), "Работа", true))
    }
}
