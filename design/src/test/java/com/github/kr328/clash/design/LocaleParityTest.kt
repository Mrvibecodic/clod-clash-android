package com.github.kr328.clash.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocaleParityTest {
    private val declarations = Regex("""<string name="([^"]+)"([^>]*)>""")

    private val pluralBlocks = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)

    private val quantities = Regex("""<item quantity="([^"]+)"""")

    private val modules = listOf("design", "service", "common")

    private val root: File = run {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile

        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return@run dir

            dir = dir.parentFile
        }

        throw AssertionError("repository root not found from ${System.getProperty("user.dir")}")
    }

    private fun resolve(module: String, locale: String): File {
        val file = File(root, "$module/src/main/res/$locale/strings.xml")

        assertTrue("${file.path} not found", file.isFile)

        return file
    }

    private fun translatableKeys(module: String, locale: String): Set<String> =
        declarations.findAll(resolve(module, locale).readText())
            .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun everyTranslatableStringHasRussian() {
        for (module in modules) {
            val base = translatableKeys(module, "values")
            val ru = translatableKeys(module, "values-ru")

            assertTrue("$module: base strings not found", base.isNotEmpty())
            assertEquals("$module: keys without values-ru", emptySet<String>(), base - ru)
            assertEquals("$module: keys only in values-ru", emptySet<String>(), ru - base)
        }
    }

    private fun plurals(module: String, locale: String): Map<String, Set<String>> =
        pluralBlocks.findAll(resolve(module, locale).readText())
            .associate { block ->
                block.groupValues[1] to quantities.findAll(block.groupValues[2]).map { it.groupValues[1] }.toSet()
            }

    @Test
    fun everyPluralHasAllForms() {
        for (module in modules) {
            val base = plurals(module, "values")
            val ru = plurals(module, "values-ru")

            assertEquals("$module: plurals differ between locales", base.keys, ru.keys)

            for ((name, forms) in base) {
                assertTrue("$module: $name lacks English forms: $forms", forms.containsAll(setOf("one", "other")))
            }

            for ((name, forms) in ru) {
                assertTrue(
                    "$module: $name lacks Russian forms: $forms",
                    forms.containsAll(setOf("one", "few", "many", "other")),
                )
            }
        }
    }

    @Test
    fun everyModuleWithStringsIsChecked() {
        val owners = root.listFiles()
            .orEmpty()
            .filter { File(it, "src/main/res/values/strings.xml").isFile }
            .map { it.name }
            .sorted()

        assertEquals(modules.sorted(), owners)
    }

    @Test
    fun theBiggestModuleIsFound() {
        assertTrue(translatableKeys("design", "values").size > 100)
    }
}
