package com.github.kr328.clash.service.store

import com.github.kr328.clash.core.DiagnosticsBootstrap
import com.github.kr328.clash.core.DiagnosticsRuntimeState
import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.model.DiagnosticsSessionAccess
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCipherTest {
    @Test
    fun `session access uses one credential snapshot`() {
        val credential = requireNotNull(
            DiagnosticsCredential.create(
                username = "operator",
                password = "tunnel-secret",
            ),
        )
        val bootstrap = DiagnosticsBootstrap(DiagnosticsRuntimeState.READY, "controller-secret", 19091)
        val session = DiagnosticsSessionAccess.from(credential, bootstrap)
        val controller = session.controller as ExternalControllerAccess.Diagnostics

        assertEquals("controller-secret", controller.secret)
        assertEquals("operator:tunnel-secret", session.diagnostics?.tunnelAuth)
        assertEquals("controller-secret", session.diagnostics?.controllerSecret)
        assertEquals(19091, session.diagnostics?.remotePort)

        val local = DiagnosticsSessionAccess.from(null, null)
        assertTrue(local.controller === ExternalControllerAccess.LocalOnly)
        assertNull(local.diagnostics)
    }

    @Test
    fun `credential keeps tunnel auth separate from controller access`() {
        val credential = DiagnosticsCredential.create(
            username = "operator",
            password = "tunnel:secret",
        )

        requireNotNull(credential)
        assertEquals("operator:tunnel:secret", credential.chiselAuth)
        assertEquals(credential, DiagnosticsCredential.decode(credential.encoded))
        assertEquals(credential, DiagnosticsCredential.decode("v2\noperator\ntunnel:secret\nold-controller\n19091"))
        assertNull(DiagnosticsCredential.decode("operator:tunnel:secret"))
    }

    @Test
    fun `credential rejects incomplete values`() {
        assertNull(DiagnosticsCredential.create("", "secret"))
        assertNull(DiagnosticsCredential.create("operator:name", "secret"))
        assertNull(DiagnosticsCredential.create("operator", ""))
        assertNull(DiagnosticsCredential.create("operator name", "secret"))
        assertNull(DiagnosticsCredential.create("operator", "line\nbreak"))
        assertNull(DiagnosticsCredential.create("operator", "пароль"))
        assertNull(DiagnosticsCredential.decode("missing-separator"))
        assertNull(DiagnosticsCredential.decode("v2\noperator\nsecret"))
    }

    @Test
    fun `encryption cipher generates its own iv`() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val plaintext = "user:password".toByteArray(StandardCharsets.UTF_8)
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")

        val iv = initializeDiagnosticsEncryptionCipher(encrypt, key)
        val ciphertext = encrypt.doFinal(plaintext)

        assertEquals(12, iv.size)
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        assertArrayEquals(plaintext, decrypt.doFinal(ciphertext))
    }
}
