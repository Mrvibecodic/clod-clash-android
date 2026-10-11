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

    private fun message(text: String, time: Long = 1) = LogMessage(LogMessage.Level.Info, text, Date(time))

    private val trimmed = "[APP] log file limit reached, older lines dropped"

    @Test
    fun `full file keeps its beginning and the freshest lines and stays within the limit`() {
        val file = folder.newFile()
        val limit = 4_000L

        LogcatWriter(file, limit).use { writer ->
            for (i in 0 until 1_000) {
                assertTrue(writer.appendMessage(message("line %04d".format(i), time = 1_000L + i)))

                assertTrue(file.length() <= limit)
            }
        }

        val lines = file.readLines()

        assertEquals("1000:Info:line 0000", lines.first())
        assertEquals("1999:Info:line 0999", lines.last())
        assertEquals(1, lines.count { it.endsWith(trimmed) })
        assertTrue(lines.none { it == "1500:Info:line 0500" })
    }

    @Test
    fun `limit counts bytes and trimmed lines stay whole and in time order`() {
        val file = folder.newFile()
        val limit = 2_000L

        LogcatWriter(file, limit).use { writer ->
            for (i in 0 until 500) {
                assertTrue(writer.appendMessage(message("журнал $i", time = 1_700_000_010_000L + i)))

                assertTrue(file.length() <= limit)
            }
        }

        val lines = file.readLines()

        assertEquals("1700000010499:Info:журнал 499", lines.last())
        assertTrue(lines.any { it.endsWith(trimmed) })

        val times = lines.map { line ->
            val parts = line.split(":", limit = 3)

            assertEquals(3, parts.size)

            parts[0].toLong()
        }

        assertEquals(times.sorted(), times)
    }

    @Test
    fun `recording goes on into a new file when the old one was deleted`() {
        val file = folder.newFile()

        LogcatWriter(file, limit = 200L).use { writer ->
            repeat(5) { writer.appendMessage(message("before $it")) }

            assertTrue(file.delete())

            repeat(20) { assertTrue(writer.appendMessage(message("after $it"))) }
        }

        assertTrue(file.exists())
        assertTrue(file.readLines().last().endsWith("after 19"))
        assertFalse(folder.root.listFiles()!!.any { it.name.endsWith(".trim") })
    }

    @Test
    fun `stale copy of an interrupted trim is removed and a fresh one is kept`() {
        val stale = folder.newFile("clash-1.log.trim").apply { setLastModified(System.currentTimeMillis() - 3_600_000) }
        val fresh = folder.newFile("clash-3.log.trim")

        LogcatWriter(folder.root.resolve("clash-2.log")).use { }

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `repeated trims of lines of different length keep one mark and the same beginning`() {
        val file = folder.newFile()
        val limit = 3_000L

        LogcatWriter(file, limit).use { writer ->
            for (i in 0 until 2_000) {
                assertTrue(writer.appendMessage(message("x".repeat(1 + i % 37) + " $i", time = 5_000L + i)))
            }
        }

        val lines = file.readLines()

        assertTrue(lines.first().startsWith("5000:Info:x 0"))
        assertEquals(1, lines.count { it.endsWith(trimmed) })
        assertTrue(lines.last().endsWith(" 1999"))
    }

    // Хвост, начавшийся с продолжения многострочного сообщения, где есть «число:»,
    // не даёт отметке об обрезке чужое время
    @Test
    fun `trim mark takes its time from a real line after a multiline message`() {
        val file = folder.newFile()

        LogcatWriter(file, limit = 3_000L).use { writer ->
            for (i in 0 until 400) {
                assertTrue(writer.appendMessage(message("dial $i\n443: refused\n2001:db8::1", time = 1_700_000_000_000L + i)))
            }
        }

        val times = file.readLines().mapNotNull { line ->
            line.substringBefore(':', "").takeIf { it.length >= 12 }?.toLong()
        }

        assertEquals(times.sorted(), times)
        assertTrue(file.readLines().any { it.endsWith(trimmed) })
    }

    // Строка на границе начала длиннее отметки: пересчёт границы при каждом
    // ужатии затягивал бы в начало прежние отметки
    @Test
    fun `beginning of the recording is fixed at the first trim`() {
        val file = folder.newFile()
        val limit = 8_000L
        val random = java.util.Random(7)

        LogcatWriter(file, limit).use { writer ->
            for (i in 0 until 3_000) {
                assertTrue(writer.appendMessage(message("y".repeat(80 + random.nextInt(220)) + " $i", time = 20_000L + i)))
            }
        }

        assertEquals(1, file.readLines().count { it.endsWith(trimmed) })
    }

    @Test
    fun `deleted file is noticed before the limit is reached`() {
        val file = folder.newFile()

        LogcatWriter(file).use { writer ->
            writer.appendMessage(message("before"))

            assertTrue(file.delete())

            repeat(300) { writer.appendMessage(message("after $it")) }
        }

        assertTrue(file.exists())
        assertTrue(file.readLines().last().endsWith("after 299"))
    }

    @Test
    fun `recording goes on when the whole log folder was deleted`() {
        val dir = folder.newFolder("logs")
        val file = dir.resolve("clash-1.log")

        LogcatWriter(file, limit = 200L).use { writer ->
            repeat(5) { writer.appendMessage(message("before $it")) }

            assertTrue(dir.deleteRecursively())

            repeat(20) { assertTrue(writer.appendMessage(message("after $it"))) }
        }

        assertTrue(file.readLines().last().endsWith("after 19"))
    }

    @Test
    fun `failed trim leaves the file open for the reason line`() {
        val file = folder.newFile()

        // Каталог на месте копии: ужатие не сможет её создать
        folder.newFolder(file.name + ".trim").resolve("busy").createNewFile()

        LogcatWriter(file, limit = 100L).use { writer ->
            var accepted = true

            for (i in 0 until 20) {
                accepted = writer.appendMessage(message("line $i"))

                if (!accepted) break
            }

            assertFalse(accepted)
            assertTrue(writer.failure != null)

            writer.appendLast(message("recording stopped"))
        }

        assertTrue(file.readLines().last().endsWith("recording stopped"))
    }

    @Test
    fun `last line is written past the limit and every line is on disk before close`() {
        val file = folder.newFile()

        LogcatWriter(file, limit = 0).use {
            it.appendLast(message("stopped"))

            assertEquals("1:Info:stopped\n", file.readText())
        }
    }
}
