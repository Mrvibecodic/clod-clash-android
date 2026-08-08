package com.github.kr328.clash.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Счётчик трафика едет из ядра в приложение ОДНИМ числом: `main.c` пакует
 * два значения в `long` — старшие 32 бита отдача, младшие приём, — и каждое
 * из них ужимает до пары «единица измерения + сотые доли» (`down_scale_traffic`).
 *
 * Из-за этого договора обычная арифметика здесь не работает: 500 в поле данных
 * значит 5.00 единиц, а не 500 байт. Проверки ниже держат ровно его — тот же
 * упаковщик, что в `core/src/main/cpp/bridge_helper.c`, только на Kotlin.
 */
class TrafficFormatTest {
    private lateinit var restore: Locale

    /**
     * Формат считает `String.format`, а он берёт разделитель дробной части
     * из локали: в русской «5,00», в английской «5.00». Локаль машины в это
     * не должна вмешиваться, поэтому на время проверок она фиксирована.
     */
    @Before
    fun setUp() {
        restore = Locale.getDefault()

        Locale.setDefault(Locale.ROOT)
    }

    @After
    fun tearDown() {
        Locale.setDefault(restore)
    }

    /** Единица измерения в двух старших битах, сотые доли — в остальных. */
    private fun scaled(unit: Int, hundredths: Long): Long =
        (unit.toLong() shl 30) or hundredths

    /** Отдача в старших 32 битах, приём — в младших. */
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
        assertEquals("12.34 GiB", packed(scaled(3, 1234), 0).trafficUpload())
    }

    @Test
    fun `отдача и приём не перетекают друг в друга`() {
        val traffic = packed(upload = scaled(3, 500), download = scaled(1, 250))

        assertEquals("5.00 GiB", traffic.trafficUpload())
        assertEquals("2.50 KiB", traffic.trafficDownload())
    }

    @Test
    fun `итог складывает обе половины до перевода в строку`() {
        // 5.00 MiB отдачи и 7.00 MiB приёма — это 12.00 MiB, а не «5.00 + 7.00».
        val traffic = packed(upload = scaled(2, 500), download = scaled(2, 700))

        assertEquals("12.00 MiB", traffic.trafficTotal())
    }

    @Test
    fun `итог поднимается на единицу выше, когда половины складываются`() {
        // 600 + 600 сотых гигабайта = 12.00 GiB.
        val traffic = packed(upload = scaled(3, 600), download = scaled(3, 600))

        assertEquals("12.00 GiB", traffic.trafficTotal())
    }

    @Test
    fun `старший бит приёма не уезжает в отдачу`() {
        // Приём около 10 GiB занимает все младшие 32 бита; знаковый сдвиг
        // вместо беззнакового показал бы здесь мусор.
        val traffic = packed(upload = scaled(0, 1), download = scaled(3, 1000))

        assertEquals("1 Bytes", traffic.trafficUpload())
        assertEquals("10.00 GiB", traffic.trafficDownload())
    }

    @Test
    fun `ровно единица измерения пока показывается байтами`() {
        // Известная особенность апстрима: границы сравниваются строгим «больше»,
        // поэтому ровно 1.00 KiB не дотягивает до своей ветки и печатается
        // числом байт. Проверка стоит здесь как зарубка: если поведение
        // поправят, тест упадёт и об этом вспомнят намеренно.
        assertEquals("102400 Bytes", packed(scaled(1, 100), 0).trafficUpload())
    }
}
