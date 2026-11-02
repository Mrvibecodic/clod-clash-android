package com.github.kr328.clash.core.model

import com.github.kr328.clash.core.model.ProviderValues.Value
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderValuesTest {
    @Test
    fun `values of the core come out as text, flags and items`() {
        assertEquals(Value.Text("debug"), ProviderValues.value("\"debug\""))
        assertEquals(Value.Text("true"), ProviderValues.value("\"true\""))
        assertEquals(Value.Text("7890"), ProviderValues.value("7890"))
        assertEquals(Value.Flag(false), ProviderValues.value("false"))
        assertEquals(Value.Items(emptyList()), ProviderValues.value("null"))
        assertEquals(
            Value.Items(listOf("https://dns.example.com/dns-query", "192.0.2.1")),
            ProviderValues.value("""["https://dns.example.com/dns-query","192.0.2.1"]"""),
        )
        assertEquals(
            Value.Items(listOf("a.example.com = 192.0.2.1", "b.example.com = 192.0.2.2, 192.0.2.3")),
            ProviderValues.value("""{"a.example.com":"192.0.2.1","b.example.com":["192.0.2.2","192.0.2.3"]}"""),
        )
    }
}
