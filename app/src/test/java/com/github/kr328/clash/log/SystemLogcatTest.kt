package com.github.kr328.clash.log

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemLogcatTest {
    @Test
    fun `адрес подписки в дампе прячется, каким бы тегом он ни пришёл`() {
        val lines = sequenceOf(
            "--------- beginning of main",
            "E Go      : panic: Get \"https://panel.example.com/sub/abcdef?token=SECRET\": dial tcp",
            "E AndroidRuntime: java.io.IOException: https://panel.example.com/sub?token=SECRET",
            "I ClodClash: App version: versionName = 1",
        )

        val expected = listOf(
            "E Go      : panic: Get \"https://panel.example.com/***\": dial tcp",
            "E AndroidRuntime: java.io.IOException: https://panel.example.com/***",
            "I ClodClash: App version: versionName = 1",
        ).joinToString("\n")

        assertEquals(expected, SystemLogcat.render(lines))
    }
}
