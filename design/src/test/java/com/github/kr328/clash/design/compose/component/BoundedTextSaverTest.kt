package com.github.kr328.clash.design.compose.component

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedTextSaverTest {
    private val scope = SaverScope { true }

    private fun saved(text: String): String? = with(boundedTextSaver) { scope.save(mutableStateOf(text)) }

    @Test
    fun `ordinary list survives screen recreation`() {
        val text = "a.example = 1.1.1.1\nb.example = 2.2.2.2"

        assertEquals(text, saved(text))
        assertEquals(text, boundedTextSaver.restore(text)?.value)
    }

    @Test
    fun `huge hosts list is kept out of the saved screen state`() {
        val hosts = (1..20_000).joinToString("\n") { "host$it.example = 10.0.0.1" }

        assertNull(saved(hosts))
    }

    @Test
    fun `list right at the limit is still saved`() {
        val text = "x".repeat(SAVED_TEXT_LIMIT)

        assertEquals(text, saved(text))
        assertNull(saved(text + "x"))
    }
}
