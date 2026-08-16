package com.github.kr328.clash.design.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class BytesStringTest {
    private lateinit var restore: Locale

    @Before
    fun setUp() {
        restore = Locale.getDefault()

        Locale.setDefault(Locale.ROOT)
    }

    @After
    fun tearDown() {
        Locale.setDefault(restore)
    }

    @Test
    fun `мелочь показывается байтами`() {
        assertEquals("0 Bytes", 0L.toBytesString())
        assertEquals("512 Bytes", 512L.toBytesString())
        assertEquals("1024 Bytes", 1024L.toBytesString())
    }

    @Test
    fun `единицы измерения растут по степеням 1024`() {
        assertEquals("1.50 KiB", (1024L + 512).toBytesString())
        assertEquals("5.00 MiB", (5L * 1024 * 1024).toBytesString())
        assertEquals("1.50 GiB", (1536L * 1024 * 1024).toBytesString())
        assertEquals("2.00 TiB", (2L * 1024 * 1024 * 1024 * 1024).toBytesString())
        assertEquals("3.00 PiB", (3L * 1024 * 1024 * 1024 * 1024 * 1024).toBytesString())
    }

    @Test
    fun `безлимит в терабайтах не ломает разряды`() {
        val huge = 1024L * 1024 * 1024 * 1024 * 1024 * 1024 * 5

        assertEquals("5.00 EiB", huge.toBytesString())
    }
}
