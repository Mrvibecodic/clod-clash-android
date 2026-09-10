package com.github.kr328.clash.core.model

import com.github.kr328.clash.core.Clash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationOverrideTest {
    private fun round(json: String): String {
        val decoded = Clash.CoreJson.decodeFromString(ConfigurationOverride.serializer(), json)

        return Clash.CoreJson.encodeToString(ConfigurationOverride.serializer(), decoded)
    }

    @Test
    fun `the geo database addresses survive a save`() {
        val encoded = round(
            """{"geox-url":{"geoip":"https://example/geoip.dat","geosite":"https://example/geosite.dat"},"mixed-port":7890}"""
        )

        assertTrue(encoded, encoded.contains(""""geoip":"https://example/geoip.dat""""))
        assertTrue(encoded, encoded.contains(""""geosite":"https://example/geosite.dat""""))
        assertTrue(encoded, encoded.contains(""""mixed-port":7890"""))
    }

    @Test
    fun `an untouched override stays empty`() {
        assertEquals("{}", round("{}"))
        assertFalse(round("""{"mixed-port":7890}""").contains("geox-url"))
    }

    @Test
    fun `an address left out stays out`() {
        val encoded = round("""{"geox-url":{"geoip":"https://example/geoip.dat"}}""")

        assertFalse(encoded, encoded.contains("mmdb"))
        assertFalse(encoded, encoded.contains("geosite"))
    }

    @Test
    fun `a key the model does not know is still dropped`() {
        assertFalse(round("""{"clod-unknown-key":"value"}""").contains("clod-unknown-key"))
    }
}
