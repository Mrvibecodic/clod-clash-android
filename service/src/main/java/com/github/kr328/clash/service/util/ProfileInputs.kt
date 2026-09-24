package com.github.kr328.clash.service.util

import java.io.File
import java.security.MessageDigest

object ProfileInputs {
    fun fingerprint(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")

        val files = listOf(dir.resolve(ProfileSwap.CONFIG_FILE)) +
            dir.resolve("providers").walk().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }

        for (file in files) {
            digest.update(file.relativeTo(dir).invariantSeparatorsPath.toByteArray())
            digest.update(0)

            if (file.isFile) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            } else {
                digest.update(1)
            }

            digest.update(0)
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
