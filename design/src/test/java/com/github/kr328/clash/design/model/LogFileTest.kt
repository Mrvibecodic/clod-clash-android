package com.github.kr328.clash.design.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogFileTest {
    @Test
    fun `имя разбирается обратно в дату`() {
        val stamp = 1786309200000L

        val parsed = LogFile.parseFromFileName("clash-$stamp.log")

        assertEquals("clash-$stamp.log", parsed?.fileName)
        assertEquals(stamp, parsed?.date?.time)
    }

    @Test
    fun `чужие имена не разбираются`() {
        assertNull(LogFile.parseFromFileName("clash-1786309200000.log.bak"))
        assertNull(LogFile.parseFromFileName("old-clash-1786309200000.log"))
        assertNull(LogFile.parseFromFileName("clash-.log"))
        assertNull(LogFile.parseFromFileName("clash-abc.log"))
        assertNull(LogFile.parseFromFileName("clash.log"))
        assertNull(LogFile.parseFromFileName(""))
    }

    @Test
    fun `созданное имя разбирается обратно`() {
        val generated = LogFile.generate()

        val parsed = LogFile.parseFromFileName(generated.fileName)

        assertEquals(generated.fileName, parsed?.fileName)
        assertEquals(generated.date.time, parsed?.date?.time)
    }
}
