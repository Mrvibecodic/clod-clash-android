package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.TunPrefs
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

fun Context.readTunPrefs(uuid: UUID): TunPrefs? {
    val file = importedDir.resolve(uuid.toString()).resolve("tun.json")

    if (!file.isFile) return null

    return try {
        json.decodeFromString(TunPrefs.serializer(), file.readText())
    } catch (e: Exception) {
        Log.w("Read tun.json of $uuid: $e", e)

        null
    }
}

fun Context.activeTunPrefs(): TunPrefs? {
    return ServiceStore(this).activeProfile?.let { readTunPrefs(it) }
}

private val TUN_STACKS = setOf("system", "gvisor", "mixed")

fun resolveTunStack(mode: String, fromProfile: String): String = when {
    mode in TUN_STACKS -> mode
    fromProfile in TUN_STACKS -> fromProfile
    else -> "system"
}
