package com.github.kr328.clash.design.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    @Test
    fun `имя файла без разделителей пути`() {
        assertTrue(ValidatorFileName("config.yaml"))
        assertTrue(ValidatorFileName("моя подписка.yaml"))

        assertFalse(ValidatorFileName("sub/config.yaml"))
        assertFalse(ValidatorFileName("*.yaml"))
        assertFalse(ValidatorFileName("a%b"))
        assertFalse(ValidatorFileName("a&b"))
        assertFalse(ValidatorFileName("a\nb"))

        assertFalse(ValidatorFileName("   "))
        assertFalse(ValidatorFileName(""))
    }

    @Test
    fun `адрес подписки только http и https`() {
        assertTrue(ValidatorHttpUrl("https://panel.example.com/sub"))
        assertFalse(ValidatorHttpUrl("http://panel.example.com/sub"))

        assertTrue(ValidatorHttpUrl("HTTPS://panel.example.com/sub"))

        assertFalse(ValidatorHttpUrl("panel.example.com/sub"))
        assertFalse(ValidatorHttpUrl("ftp://panel.example.com/sub"))
        assertFalse(ValidatorHttpUrl(""))
    }

    @Test
    fun `интервал автообновления не меньше четверти часа`() {
        assertTrue(ValidatorAutoUpdateInterval(""))
        assertTrue(ValidatorAutoUpdateInterval("15"))
        assertTrue(ValidatorAutoUpdateInterval("1440"))

        assertFalse(ValidatorAutoUpdateInterval("14"))
        assertFalse(ValidatorAutoUpdateInterval("0"))
        assertFalse(ValidatorAutoUpdateInterval("-60"))

        assertFalse(ValidatorAutoUpdateInterval("часто"))
    }

    @Test
    fun `непустое значение`() {
        assertTrue(ValidatorNotBlank("x"))
        assertFalse(ValidatorNotBlank(""))
        assertFalse(ValidatorNotBlank("   "))
        assertTrue(ValidatorAcceptAll(""))
    }
}
