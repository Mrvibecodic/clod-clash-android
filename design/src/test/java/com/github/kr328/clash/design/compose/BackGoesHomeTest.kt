package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.SubScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackGoesHomeTest {
    @Test
    fun `на Главной назад не перехватывается — выход из приложения`() {
        assertFalse(MainScreenState(selectedTab = MainTab.Home).backGoesHome)
    }

    @Test
    fun `на остальных вкладках назад ведёт на Главную`() {
        MainTab.entries.filter { it != MainTab.Home }.forEach { tab ->
            assertTrue(tab.name, MainScreenState(selectedTab = tab).backGoesHome)
        }
    }

    @Test
    fun `открытый экран внутри вкладки сначала закрывается сам`() {
        SubScreen.entries.forEach { screen ->
            assertFalse(screen.name, MainScreenState(selectedTab = MainTab.More, subScreen = screen).backGoesHome)
        }
    }
}
