package com.github.kr328.clash.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class TrafficFormatTest {
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

    private fun scaled(unit: Int, hundredths: Long): Long =
        (unit.toLong() shl 30) or hundredths

    private fun packed(upload: Long, download: Long): Long =
        (upload shl 32) or download

    @Test
    fun `байты показываются без дробной части`() {
        val traffic = packed(upload = scaled(0, 512), download = scaled(0, 0))

        assertEquals("512 Bytes", traffic.trafficUpload())
        assertEquals("0 Bytes", traffic.trafficDownload())
    }

    @Test
    fun `каждая единица измерения разбирается по своему коду`() {
        assertEquals("5.00 KiB", packed(scaled(1, 500), 0).trafficUpload())
        assertEquals("5.00 MiB", packed(scaled(2, 500), 0).trafficUpload())
        assertEquals("5.00 GiB", packed(scaled(3, 500), 0).trafficUpload())
    }

    @Test
    fun `сотые доли не теряются`() {
        assertEquals("1.50 MiB", packed(scaled(2, 150), 0).trafficUpload())
        assertEquals("12.3 GiB", packed(scaled(3, 1234), 0).trafficUpload())
    }

    @Test
    fun `отдача и приём не перетекают друг в друга`() {
        val traffic = packed(upload = scaled(3, 500), download = scaled(1, 250))

        assertEquals("5.00 GiB", traffic.trafficUpload())
        assertEquals("2.50 KiB", traffic.trafficDownload())
    }

    @Test
    fun `итог складывает обе половины до перевода в строку`() {
        val traffic = packed(upload = scaled(2, 500), download = scaled(2, 700))

        assertEquals("12.0 MiB", traffic.trafficTotal())
    }

    @Test
    fun `итог поднимается на единицу выше, когда половины складываются`() {
        val traffic = packed(upload = scaled(3, 600), download = scaled(3, 600))

        assertEquals("12.0 GiB", traffic.trafficTotal())
    }

    @Test
    fun `старший бит приёма не уезжает в отдачу`() {
        val traffic = packed(upload = scaled(0, 1), download = scaled(3, 1000))

        assertEquals("1 Bytes", traffic.trafficUpload())
        assertEquals("10.0 GiB", traffic.trafficDownload())
    }

    @Test
    fun `ровно единица измерения показывается своей единицей`() {
        assertEquals("1.00 KiB", packed(scaled(1, 100), 0).trafficUpload())
        assertEquals("1.00 MiB", packed(scaled(2, 100), 0).trafficUpload())
        assertEquals("1.00 GiB", packed(scaled(3, 100), 0).trafficUpload())
    }

    @Test
    fun `код гигабайта не запирает показ на гигабайтах`() {
        assertEquals("1.00 TiB", packed(scaled(3, 102400), 0).trafficUpload())
        assertEquals("1.01 TiB", packed(scaled(3, 103500), 0).trafficUpload())
        assertEquals("1.00 PiB", packed(scaled(3, 104857600), 0).trafficUpload())
    }

    @Test
    fun `уведомление и главный экран форматируют одно и то же`() {
        val samples = listOf(
            packed(scaled(0, 512), scaled(0, 1)),
            packed(scaled(1, 100), scaled(2, 150)),
            packed(scaled(3, 1234), scaled(3, 102400)),
        )

        for (traffic in samples) {
            assertEquals(traffic.bytesUpload().toBytesString(), traffic.trafficUpload())
            assertEquals(traffic.bytesDownload().toBytesString(), traffic.trafficDownload())
        }
    }

    @Test
    fun `байты отдаются настоящими, а не сотыми долями`() {
        assertEquals(5L * 1024 * 1024 * 1024, packed(scaled(3, 500), 0).bytesUpload())
        assertEquals(1536L * 1024, packed(scaled(2, 150), 0).bytesUpload())
        assertEquals(5L * 1024, packed(scaled(1, 500), 0).bytesUpload())
    }

    @Test
    fun `меньше килобайта отдаётся как есть`() {
        assertEquals(512L, packed(scaled(0, 512), 0).bytesUpload())
        assertEquals(0L, packed(scaled(0, 0), 0).bytesUpload())
    }

    @Test
    fun `половины байтов не перетекают друг в друга`() {
        val traffic = packed(upload = scaled(3, 500), download = scaled(1, 250))

        assertEquals(5L * 1024 * 1024 * 1024, traffic.bytesUpload())
        assertEquals(2560L, traffic.bytesDownload())
    }
}
