package com.github.kr328.clash.log

import com.github.kr328.clash.core.model.LogMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Date

class LogcatWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun message(text: String) = LogMessage(LogMessage.Level.Info, text, Date(1))

    @Test
    fun `line that does not fit is refused and the file stays within the limit`() {
        val file = folder.newFile()
        val line = "1:Info:0123456789\n".length.toLong()

        LogcatWriter(file, limit = line * 2 + 1).use {
            assertTrue(it.appendMessage(message("0123456789")))
            assertTrue(it.appendMessage(message("0123456789")))
            assertFalse(it.appendMessage(message("0123456789")))
        }

        assertEquals(line * 2, file.length())
    }

    @Test
    fun `limit counts bytes, not characters`() {
        val file = folder.newFile()
        val ascii = "1:Info:\n".length.toLong()

        LogcatWriter(file, limit = ascii + 8).use {
            assertFalse(it.appendMessage(message("журнал")))
            assertTrue(it.appendMessage(message("log")))
        }

        assertEquals(ascii + 3, file.length())
    }

    @Test
    fun `last line is written past the limit and every line is on disk before close`() {
        val file = folder.newFile()

        LogcatWriter(file, limit = 0).use {
            assertFalse(it.appendMessage(message("dropped")))

            it.appendLast(message("stopped"))

            assertEquals("1:Info:stopped\n", file.readText())
        }
    }
}
