package com.github.kr328.clash.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WriteAtomicallyTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `replaces the file and leaves no temporary`() {
        val file = File(folder.root, "state.json").apply { writeText("old") }

        file.writeAtomically { it.writeText("new") }

        assertEquals("new", file.readText())
        assertFalse(File("${file.path}.tmp").exists())
    }

    @Test
    fun `failed write keeps the old file and removes the temporary`() {
        val file = File(folder.root, "state.json").apply { writeText("old") }
        val temp = File(folder.root, "state.json.new")

        assertThrows(IllegalStateException::class.java) {
            file.writeAtomically(temp) {
                it.writeText("half")
                error("disk gone")
            }
        }

        assertEquals("old", file.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun `failed replace is reported and removes the temporary`() {
        val target = File(folder.root, "busy").apply { mkdir() }
        File(target, "inside").writeText("x")
        val temp = File(folder.root, "busy.tmp")

        assertThrows(IOException::class.java) {
            target.writeAtomically(temp) { it.writeText("new") }
        }

        assertEquals("x", File(target, "inside").readText())
        assertFalse(temp.exists())
    }

    @Test
    fun `early return skips the replace`() {
        val file = File(folder.root, "icon.png").apply { writeText("old") }
        val temp = File(folder.root, "icon.png.tmp")

        fun download(): Boolean {
            file.writeAtomically(temp) {
                it.writeText("too big")
                return false
            }

            return true
        }

        assertFalse(download())
        assertEquals("old", file.readText())
        assertFalse(temp.exists())
    }
}
