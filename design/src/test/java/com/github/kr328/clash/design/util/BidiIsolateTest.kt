package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BidiIsolateTest {
    private val fsi = '⁨'

    private val pdi = '⁩'

    @Test
    fun `пустая строка остаётся пустой`() {
        assertEquals("", "".bidiIsolated())
    }

    @Test
    fun `латинское имя оборачивается изолятом`() {
        assertEquals("${fsi}RU 1$pdi", "RU 1".bidiIsolated())
    }

    @Test
    fun `имя справа налево оборачивается изолятом`() {
        assertEquals("${fsi}سيرفر 1$pdi", "سيرفر 1".bidiIsolated())
    }

    @Test
    fun `пробел не считается пустой строкой`() {
        assertEquals("$fsi $pdi", " ".bidiIsolated())
    }

    @Test
    fun `повторная изоляция оборачивает второй раз`() {
        assertEquals("$fsi${fsi}RU$pdi$pdi", "RU".bidiIsolated().bidiIsolated())
    }
}
