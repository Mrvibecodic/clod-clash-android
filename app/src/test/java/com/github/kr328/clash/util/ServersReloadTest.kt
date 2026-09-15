package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ServersReloadTest {
    @Test
    fun `после события панель перечитывается целиком`() {
        assertEquals(ServersReload.Panel, serversReload(panelCurrent = false, liveGroups = true))
        assertEquals(ServersReload.Panel, serversReload(panelCurrent = false, liveGroups = false))
    }

    @Test
    fun `при актуальной панели с ядром обновляется только выбранная группа`() {
        assertEquals(ServersReload.SelectedGroup, serversReload(panelCurrent = true, liveGroups = true))
    }

    @Test
    fun `при актуальной панели без ядра ничего не запрашивается`() {
        assertEquals(ServersReload.Nothing, serversReload(panelCurrent = true, liveGroups = false))
    }
}
