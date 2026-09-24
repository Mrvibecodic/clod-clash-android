package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.InboundPrefs
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

private val inboundJson = Json { ignoreUnknownKeys = true }

fun Context.readInboundPrefs(uuid: UUID): InboundPrefs? {
    return try {
        ProfileSwap.read(importedDir.resolve(uuid.toString()), "inbound.json") { file ->
            inboundJson.decodeFromString(InboundPrefs.serializer(), file.readText())
        }
    } catch (e: Exception) {
        Log.w("Read inbound.json of $uuid: $e", e)

        null
    }
}

suspend fun Context.activeLocalProxyPort(): Int? = withContext(Dispatchers.IO) {
    runCatching {
        ServiceStore(this@activeLocalProxyPort).activeProfile
            ?.let { readInboundPrefs(it) }
            ?.localProxyPort
            ?.takeIf { it > 0 }
    }.getOrNull()
}
