package com.github.kr328.clash.core.model

import com.github.kr328.clash.core.Clash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyGroupNamesTest {
    @Test
    fun `direct mode has no groups and is marked direct`() {
        val decoded = Clash.decodeGroupNames("""{"direct":true,"names":[],"icons":{}}""")

        assertTrue(decoded.direct)
        assertTrue(decoded.names.isEmpty())
        assertTrue(decoded.icons.isEmpty())
    }

    @Test
    fun `names keep their order and icons stay attached to their groups`() {
        val decoded = Clash.decodeGroupNames(
            """{"direct":false,"names":["Auto","Proxy","GLOBAL"],"icons":{"Proxy":"https://example/p.png"}}"""
        )

        assertFalse(decoded.direct)
        assertEquals(listOf("Auto", "Proxy", "GLOBAL"), decoded.names)
        assertEquals(mapOf("Proxy" to "https://example/p.png"), decoded.icons)
    }

    @Test
    fun `main group comes from the core and is absent in direct mode`() {
        val rule = Clash.decodeGroupNames("""{"direct":false,"names":["Auto","Proxy"],"icons":{},"main":"Proxy"}""")
        val direct = Clash.decodeGroupNames("""{"direct":true,"names":[],"icons":{}}""")

        assertEquals("Proxy", rule.main)
        assertNull(direct.main)
    }

    @Test
    fun `missing fields fall back to an empty snapshot`() {
        assertEquals(ProxyGroupNames(), Clash.decodeGroupNames("{}"))
    }
}
