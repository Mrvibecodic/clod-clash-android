package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.service.model.InboundPrefs
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

fun Context.readInboundPrefs(uuid: UUID): InboundPrefs? =
    readProfileJson(uuid, "inbound.json", InboundPrefs.serializer())

suspend fun Context.activeLocalProxyPort(): Int? = withContext(Dispatchers.IO) {
    runCatching {
        ServiceStore(this@activeLocalProxyPort).activeProfile
            ?.let { readInboundPrefs(it) }
            ?.localProxyPort
            ?.takeIf { it > 0 }
    }.getOrNull()
}
