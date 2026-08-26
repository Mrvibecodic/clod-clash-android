package com.github.kr328.clash.service.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.kr328.clash.core.model.DiagnosticsAccess
import com.github.kr328.clash.service.DiagnosticsPreferenceProvider
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun initializeDiagnosticsEncryptionCipher(cipher: Cipher, key: SecretKey): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.iv
}

data class DiagnosticsCredential private constructor(
    val username: String,
    val password: String,
    val controllerSecret: String,
    val remotePort: Int,
) {
    val chiselAuth: String
        get() = "$username:$password"

    val encoded: String
        get() = listOf(VERSION, username, password, controllerSecret, remotePort.toString())
            .joinToString("\n")

    companion object {
        private const val VERSION = "v2"

        fun create(
            username: String,
            password: String,
            controllerSecret: String,
            remotePort: Int,
        ): DiagnosticsCredential? {
            if (!username.isDiagnosticsCredentialComponent() || ':' in username ||
                !password.isDiagnosticsCredentialComponent() ||
                !controllerSecret.isDiagnosticsCredentialComponent() ||
                remotePort !in DiagnosticsAccess.MIN_REMOTE_PORT..DiagnosticsAccess.MAX_REMOTE_PORT
            ) return null
            return DiagnosticsCredential(username, password, controllerSecret, remotePort)
        }

        fun decode(value: String): DiagnosticsCredential? {
            val parts = value.split('\n')
            if (parts.size != 5 || parts[0] != VERSION) return null
            return create(
                username = parts[1],
                password = parts[2],
                controllerSecret = parts[3],
                remotePort = parts[4].toIntOrNull() ?: return null,
            )
        }
    }
}

private fun String.isDiagnosticsCredentialComponent(): Boolean =
    isNotEmpty() && all { it.code in 0x21..0x7e }

enum class DiagnosticsCredentialReadStatus {
    Missing,
    Success,
    InvalidDiscarded,
}

data class DiagnosticsCredentialReadResult(
    val status: DiagnosticsCredentialReadStatus,
    val credential: DiagnosticsCredential?,
)

/** Device-bound, private storage for diagnostics access. */
class DiagnosticsCredentialStore(context: Context) {
    private val preferences = DiagnosticsPreferenceProvider.createSharedPreferencesFromContext(context)

    /** Returns only a successfully decrypted and validated credential; invalid state fails closed. */
    fun read(): DiagnosticsCredential? = readResult().credential

    fun readResult(): DiagnosticsCredentialReadResult {
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null)
        val encodedIv = preferences.getString(IV, null)
        if (encodedCiphertext == null && encodedIv == null) {
            return DiagnosticsCredentialReadResult(DiagnosticsCredentialReadStatus.Missing, null)
        }
        if (encodedCiphertext == null || encodedIv == null) {
            discardInvalidCredential()
            return DiagnosticsCredentialReadResult(DiagnosticsCredentialReadStatus.InvalidDiscarded, null)
        }

        val credential = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
                .toString(StandardCharsets.UTF_8)
            DiagnosticsCredential.decode(plaintext) ?: error("invalid diagnostics credential")
        }.getOrElse {
            discardInvalidCredential()
            return DiagnosticsCredentialReadResult(DiagnosticsCredentialReadStatus.InvalidDiscarded, null)
        }
        return DiagnosticsCredentialReadResult(DiagnosticsCredentialReadStatus.Success, credential)
    }

    private fun discardInvalidCredential() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        preferences.edit().clear().commit()
    }

    fun save(credential: DiagnosticsCredential): Boolean {
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = initializeDiagnosticsEncryptionCipher(cipher, key())
            val ciphertext = cipher.doFinal(credential.encoded.toByteArray(StandardCharsets.UTF_8))
            preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
        }.getOrDefault(false)
    }

    fun clear(): Boolean {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        return preferences.edit().clear().commit()
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clod_diagnostics_auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
}
