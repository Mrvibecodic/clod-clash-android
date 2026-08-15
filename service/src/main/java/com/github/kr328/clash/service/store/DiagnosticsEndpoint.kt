package com.github.kr328.clash.service.store

import java.net.URI
import java.net.URISyntaxException

const val DEFAULT_DIAGNOSTICS_ENDPOINT = ""

fun normalizeDiagnosticsEndpoint(rawEndpoint: String): String? {
    return try {
        val endpoint = URI(rawEndpoint.trim())
        if (
            !endpoint.scheme.equals("https", ignoreCase = true) ||
            endpoint.host.isNullOrBlank() ||
            endpoint.userInfo != null ||
            (endpoint.port != -1 && endpoint.port !in 1..65535) ||
            (endpoint.path.isNotEmpty() && endpoint.path != "/") ||
            endpoint.query != null ||
            endpoint.fragment != null
        ) return null

        URI("https", null, endpoint.host.lowercase(), endpoint.port, null, null, null).toASCIIString()
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
