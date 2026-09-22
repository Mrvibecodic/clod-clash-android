package com.github.kr328.clash.design.model

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupSelectionTest {
    @Test
    fun `открытая группа находится по имени, когда список меняет состав`() {
        val live = listOf("Proxy", "YouTube", "Telegram")

        assertEquals(1, groupIndexOf(live, "YouTube", fallback = 2))
    }

    @Test
    fun `пропавшая группа оставляет прежний индекс в пределах списка`() {
        assertEquals(1, groupIndexOf(listOf("Proxy", "YouTube"), "Gone", fallback = 1))
        assertEquals(1, groupIndexOf(listOf("Proxy", "YouTube"), null, fallback = 5))
        assertEquals(0, groupIndexOf(emptyList(), "YouTube", fallback = 3))
    }
}
