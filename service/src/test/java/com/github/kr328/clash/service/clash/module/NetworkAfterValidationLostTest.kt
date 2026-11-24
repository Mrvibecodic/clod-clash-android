package com.github.kr328.clash.service.clash.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkAfterValidationLostTest {
    @Test
    fun `Wi-Fi без интернета уходит на проверенную SIM, пришедшую раньше`() {
        // SIM пришла, пока Wi-Fi ещё считался проверенным, и была отвергнута;
        // теперь у Wi-Fi проверка пропала и лучшая — SIM
        assertEquals("sim", networkAfterValidationLost("wifi", "wifi", "sim", preferredValidated = true))
    }

    @Test
    fun `SIM на миг без проверки не меняется на Wi-Fi без интернета`() {
        assertNull(networkAfterValidationLost("sim", "sim", "wifi", preferredValidated = false))
    }

    @Test
    fun `без другой сети остаётся на прежней`() {
        assertNull(networkAfterValidationLost("wifi", "wifi", "wifi", preferredValidated = false))
        assertNull(networkAfterValidationLost("wifi", "wifi", null, preferredValidated = false))
    }

    @Test
    fun `потеря проверки не у текущей сети ничего не меняет`() {
        assertNull(networkAfterValidationLost("sim", "wifi", "sim", preferredValidated = true))
    }

    @Test
    fun `до первой сети ничего не меняется`() {
        assertNull(networkAfterValidationLost(null, "wifi", "sim", preferredValidated = true))
    }
}
