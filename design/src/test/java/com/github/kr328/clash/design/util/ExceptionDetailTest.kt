package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExceptionDetailTest {
    @Test
    fun `исключение без текста называется своим классом`() {
        assertEquals("java.lang.IllegalStateException", exceptionDetail(IllegalStateException()))
        assertEquals("java.lang.IllegalStateException", exceptionDetail(IllegalStateException(" ")))
    }

    @Test
    fun `текст исключения уходит в детали целиком`() {
        val message = "queryProxyGroup: java.lang.RuntimeException: x"

        assertEquals(message, exceptionDetail(IllegalStateException(message)))
    }

    @Test
    fun `токен из адреса подписки в детали не попадает`() {
        val detail = exceptionDetail(RuntimeException("Get \"https://host.example/sub?token=abc\": dial tcp"))

        assertFalse(detail.contains("abc"))
        assertEquals("Get \"https://host.example/***\": dial tcp", detail)
    }
}
