package com.github.kr328.clash.design.model

import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.ReliabilityState
import com.github.kr328.clash.design.compose.screen.SubScreen
import com.github.kr328.clash.design.compose.screen.UpdateState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsBypassTest {
    @Test
    fun `служба работает — туннель только для себя и настройки дают то же`() {
        assertTrue(appsBypassVpn(running = true, tunnelOnlySelf = true, plannedOnlySelf = true, vpnMode = true))
    }

    @Test
    fun `служба работает — приложения уже выбраны, туннель ещё пересобирается`() {
        assertFalse(appsBypassVpn(running = true, tunnelOnlySelf = true, plannedOnlySelf = false, vpnMode = true))
    }

    @Test
    fun `служба работает — выбор снят, туннель ещё прежний`() {
        assertFalse(appsBypassVpn(running = true, tunnelOnlySelf = false, plannedOnlySelf = true, vpnMode = true))
    }

    @Test
    fun `служба работает без туннеля — приложения ни при чём`() {
        assertFalse(appsBypassVpn(running = true, tunnelOnlySelf = null, plannedOnlySelf = true, vpnMode = false))
        assertFalse(appsBypassVpn(running = true, tunnelOnlySelf = null, plannedOnlySelf = true, vpnMode = true))
    }

    @Test
    fun `служба не работает — по тому, что соберётся при включении`() {
        assertTrue(appsBypassVpn(running = false, tunnelOnlySelf = null, plannedOnlySelf = true, vpnMode = true))
        assertFalse(appsBypassVpn(running = false, tunnelOnlySelf = null, plannedOnlySelf = false, vpnMode = true))
    }

    @Test
    fun `служба не работает в режиме без VPN — плашки нет`() {
        assertFalse(appsBypassVpn(running = false, tunnelOnlySelf = null, plannedOnlySelf = true, vpnMode = false))
    }

    @Test
    fun `лист открывается поводом`() {
        assertTrue(appsPromptAfter(prompt = false, before = true, bypass = true, running = true, opportunity = true))
    }

    @Test
    fun `после «Позже» лист без повода не возвращается`() {
        assertFalse(appsPromptAfter(prompt = false, before = true, bypass = true, running = true, opportunity = false))
    }

    @Test
    fun `открытый лист остаётся, пока причина есть`() {
        assertTrue(appsPromptAfter(prompt = true, before = true, bypass = true, running = true, opportunity = false))
    }

    @Test
    fun `приложения только что ушли мимо VPN — лист без повода`() {
        assertTrue(appsPromptAfter(prompt = false, before = false, bypass = true, running = true, opportunity = false))
    }

    @Test
    fun `первое чтение без повода листа не открывает`() {
        assertFalse(appsPromptAfter(prompt = false, before = null, bypass = true, running = true, opportunity = false))
    }

    @Test
    fun `причины нет — лист закрывается сам`() {
        assertFalse(appsPromptAfter(prompt = true, before = true, bypass = false, running = true, opportunity = true))
    }

    @Test
    fun `служба не работает — листа нет, даже с поводом`() {
        assertFalse(appsPromptAfter(prompt = true, before = true, bypass = true, running = false, opportunity = true))
    }

    private val asked = MainScreenState(appsBypass = true, appsPrompt = true)

    @Test
    fun `лист виден на Главной`() {
        assertTrue(asked.appsSheetShown)
    }

    @Test
    fun `лист не поверх другой вкладки и подэкрана`() {
        assertFalse(asked.copy(selectedTab = MainTab.Servers).appsSheetShown)
        assertFalse(asked.copy(subScreen = SubScreen.About).appsSheetShown)
    }

    @Test
    fun `лист ждёт, пока закроются другие окна и листы`() {
        assertFalse(asked.copy(notificationPrompt = true).appsSheetShown)
        assertFalse(asked.copy(reliability = ReliabilityState(prompt = true)).appsSheetShown)
        assertFalse(asked.copy(freezeSheet = true).appsSheetShown)
        assertFalse(asked.copy(update = UpdateState(version = "1.0")).appsSheetShown)
    }

    @Test
    fun `без запроса листа нет`() {
        assertFalse(asked.copy(appsPrompt = false).appsSheetShown)
    }
}
