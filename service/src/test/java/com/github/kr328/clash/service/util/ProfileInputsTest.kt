package com.github.kr328.clash.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileInputsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun profile(vararg files: Pair<String, String>): File {
        val d = tmp.newFolder()
        files.forEach { (n, body) -> d.resolve(n).apply { parentFile?.mkdirs() }.writeText(body) }
        return d
    }

    private val base = arrayOf(
        "config.yaml" to "proxies: []",
        "providers/proxy/a.yaml" to "a",
        "providers/rules/b.yaml" to "b",
        "panel.json" to "{\"quota\":1}",
    )

    @Test
    fun `одинаковые конфиг и провайдеры дают одинаковый отпечаток`() {
        assertEquals(
            ProfileInputs.fingerprint(profile(*base)),
            ProfileInputs.fingerprint(profile(*base)),
        )
    }

    @Test
    fun `файлы для интерфейса на отпечаток не влияют`() {
        val changed = base.map { if (it.first == "panel.json") it.first to "{\"quota\":2}" else it } +
            listOf("migration.json" to "{}", "alerts.json" to "{}")

        assertEquals(
            ProfileInputs.fingerprint(profile(*base)),
            ProfileInputs.fingerprint(profile(*changed.toTypedArray())),
        )
    }

    @Test
    fun `изменённый конфиг меняет отпечаток`() {
        val changed = base.map { if (it.first == "config.yaml") it.first to "proxies: [x]" else it }

        assertNotEquals(
            ProfileInputs.fingerprint(profile(*base)),
            ProfileInputs.fingerprint(profile(*changed.toTypedArray())),
        )
    }

    @Test
    fun `изменённое содержимое провайдера меняет отпечаток`() {
        val changed = base.map { if (it.first == "providers/rules/b.yaml") it.first to "b2" else it }

        assertNotEquals(
            ProfileInputs.fingerprint(profile(*base)),
            ProfileInputs.fingerprint(profile(*changed.toTypedArray())),
        )
    }

    @Test
    fun `докачанный провайдер меняет отпечаток`() {
        assertNotEquals(
            ProfileInputs.fingerprint(profile(*base)),
            ProfileInputs.fingerprint(profile(*base, "providers/rules/c.yaml" to "c")),
        )
    }

    @Test
    fun `переименованный провайдер меняет отпечаток`() {
        val renamed = base.map { if (it.first == "providers/proxy/a.yaml") "providers/proxy/z.yaml" to it.second else it }

        assertNotEquals(
            ProfileInputs.fingerprint(profile(*base)),
            ProfileInputs.fingerprint(profile(*renamed.toTypedArray())),
        )
    }

    @Test
    fun `профиль без конфига и провайдеров не падает`() {
        assertEquals(
            ProfileInputs.fingerprint(profile()),
            ProfileInputs.fingerprint(profile()),
        )
    }
}
