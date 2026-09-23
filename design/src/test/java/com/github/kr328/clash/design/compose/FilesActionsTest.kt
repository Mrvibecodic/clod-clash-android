package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.screen.FilesState
import com.github.kr328.clash.design.model.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesActionsTest {
    private val providers = File("providers", "providers", 0, 0, isDirectory = true)
    private val config = File("config.yaml", "config.yaml", 0, 0, isDirectory = false)
    private val filled = File("config.yaml", "config.yaml", 10, 0, isDirectory = false)

    @Test
    fun `a folder in the root offers nothing`() {
        assertFalse(FilesState(inBaseDir = true).hasActions(providers))
        assertFalse(FilesState(inBaseDir = true, configurationEditable = true).hasActions(providers))
    }

    @Test
    fun `an empty read-only configuration offers nothing`() {
        assertFalse(FilesState(inBaseDir = true, configurationEditable = false).hasActions(config))
    }

    @Test
    fun `a configuration is exported when filled and imported when editable`() {
        assertTrue(FilesState(inBaseDir = true, configurationEditable = false).exportable(filled))
        assertFalse(FilesState(inBaseDir = true, configurationEditable = false).importable(filled))
        assertTrue(FilesState(inBaseDir = true, configurationEditable = true).importable(config))
    }
}
