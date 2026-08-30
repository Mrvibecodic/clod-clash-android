package com.github.kr328.clash.core.util

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
        assertEquals("1023 Bytes", 1023L.toBytesString())
    }

    @Test
    fun `ровно единица измерения показывается своей единицей`() {
        assertEquals("1.00 KiB", 1024L.toBytesString())
        assertEquals("1.00 MiB", (1024L * 1024).toBytesString())
        assertEquals("1.00 GiB", (1024L * 1024 * 1024).toBytesString())
        assertEquals("1.00 TiB", (1024L * 1024 * 1024 * 1024).toBytesString())
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
    fun `значение, которое округлилось бы до тысячи, поднимает разряд`() {
        assertEquals("1.00 MiB", 1048571L.toBytesString())
        assertEquals("1.00 GiB", (1024L * 1024 * 1024 - 4096).toBytesString())
    }

    @Test
    fun `точность как у настольного клиента - три значащих разряда`() {
        assertEquals("212 MiB", (212L * 1024 * 1024).toBytesString())
        assertEquals("12.3 GiB", (12L * 1024 * 1024 * 1024 + 322122547).toBytesString())
        assertEquals("1.40 GiB", (1024L * 1024 * 1024 + 429496730).toBytesString())
    }

    @Test
    fun `безлимит в терабайтах не ломает разряды`() {
        val huge = 1024L * 1024 * 1024 * 1024 * 1024 * 1024 * 5

        assertEquals("5.00 EiB", huge.toBytesString())
    }
}
