package com.github.kr328.clash.service.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsEndpointTest {
    @Test
    fun normalizesHttpsOrigin() {
        assertEquals(
            "https://example.com:8443",
            normalizeDiagnosticsEndpoint("  HTTPS://Example.COM:8443/  "),
        )
    }

    @Test
    fun rejectsNonOriginEndpoints() {
        listOf(
            "",
            "http://example.com",
            "https://user:password@example.com",
            "https://example.com/path",
            "https://example.com?query=value",
            "https://example.com#fragment",
            "https://exa%mple.com",
            "https://example.com:0",
            "https://example.com:65536",
        ).forEach { endpoint ->
            assertNull(endpoint, normalizeDiagnosticsEndpoint(endpoint))
        }
    }
}
