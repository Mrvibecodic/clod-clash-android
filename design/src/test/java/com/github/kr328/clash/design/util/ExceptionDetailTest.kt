package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.HumanMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ExceptionDetailTest {
    private class Unavailable(message: String) : IOException(message), HumanMessage

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

    @Test
    fun `готовый текст для человека идёт в плашку`() {
        assertEquals("Служба не отвечает", humanText(Unavailable("Служба не отвечает")))
    }

    @Test
    fun `прочие сбои показываются заголовком, причина — в деталях`() {
        assertNull(humanText(IOException("Broken pipe")))
        assertNull(humanText(IllegalStateException("boom")))
    }

    @Test
    fun `пустой текст для человека не становится пустой плашкой`() {
        assertNull(humanText(Unavailable("")))
    }
}
