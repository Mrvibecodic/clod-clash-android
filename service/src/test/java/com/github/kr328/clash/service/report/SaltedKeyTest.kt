package com.github.kr328.clash.service.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SaltedKeyTest {
    private val key = "0123456789abcdef"

    @Test
    fun `ключ сети с солью — той же длины, у одной установки один, у разных разный`() {
        val salted = saltedKey("salt-a", key)

        assertEquals(key.length, salted.length)
        assertEquals(salted, saltedKey("salt-a", key))
        assertNotEquals(salted, saltedKey("salt-b", key))
        assertNotEquals(key, salted)
    }
}
