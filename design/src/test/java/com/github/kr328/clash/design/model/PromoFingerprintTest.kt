package com.github.kr328.clash.design.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PromoFingerprintTest {
    @Test
    fun `тот же текст даёт тот же отпечаток`() {
        assertEquals(promoFingerprint("Скидка 20% до пятницы"), promoFingerprint("Скидка 20% до пятницы"))
    }

    @Test
    fun `новый текст промо даёт новый отпечаток`() {
        assertNotEquals(promoFingerprint("Скидка 20% до пятницы"), promoFingerprint("Скидка 30% до пятницы"))
    }

    @Test
    fun `отпечаток не хранит сам текст`() {
        val fingerprint = promoFingerprint("Скидка 20% до пятницы")

        assertEquals(64, fingerprint.length)
        assertFalse(fingerprint.contains("Скидка"))
    }
}
