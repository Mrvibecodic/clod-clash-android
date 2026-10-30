package com.github.kr328.clash.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TransferTest {
    private val payload = ByteArray(200_000) { it.toByte() }

    @Test
    fun `copies everything up to the limit`() = runBlocking {
        val output = ByteArrayOutputStream()
        var reported = 0L

        val copied = Transfer.copy(ByteArrayInputStream(payload), output, payload.size.toLong()) { _, _, total -> reported = total }

        assertEquals(payload.size.toLong(), copied)
        assertEquals(copied, reported)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `one byte over the limit is refused`() {
        assertThrows(Transfer.TooLarge::class.java) {
            runBlocking { Transfer.copy(ByteArrayInputStream(payload), ByteArrayOutputStream(), payload.size - 1L) }
        }
    }

    @Test
    fun `chunks cover the whole stream in order`() = runBlocking {
        val seen = ByteArrayOutputStream()

        Transfer.copy(ByteArrayInputStream(payload), ByteArrayOutputStream(), Long.MAX_VALUE) { buffer, read, _ -> seen.write(buffer, 0, read) }

        assertArrayEquals(payload, seen.toByteArray())
    }
}
