package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class FailureTest {
    @Test
    fun `служба не отвечает — человеческий текст идёт в плашку`() {
        assertEquals("Служба не отвечает", failureText(ServiceUnavailableException("Служба не отвечает")))
    }

    @Test
    fun `прочие сбои показываются заголовком, причина — в деталях`() {
        assertNull(failureText(IOException("Broken pipe")))
        assertNull(failureText(IllegalStateException("boom")))
    }

    @Test
    fun `пустой текст службы не становится пустой плашкой`() {
        assertNull(failureText(ServiceUnavailableException("")))
    }
}
