package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.TunPrefs
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

fun Context.readTunPrefs(uuid: UUID): TunPrefs? {
    return try {
        ProfileSwap.read(importedDir.resolve(uuid.toString()), "tun.json") { file ->
            json.decodeFromString(TunPrefs.serializer(), file.readText())
        }
    } catch (e: Exception) {
        Log.w("Read tun.json of $uuid: $e", e)

        null
    }
}

fun Context.activeTunPrefs(): TunPrefs? {
    return ServiceStore(this).activeProfile?.let { readTunPrefs(it) }
}

// Набор для подписки совпадает с NormalizeTunStack в native/config/panel/tun.go,
// mips доступен только явным выбором в настройках
private val PROFILE_TUN_STACKS = setOf("system", "gvisor", "mixed")

private val USER_TUN_STACKS = PROFILE_TUN_STACKS + "mips"

fun resolveTunStack(mode: String, fromProfile: String): String = when {
    mode in USER_TUN_STACKS -> mode
    fromProfile in PROFILE_TUN_STACKS -> fromProfile
    else -> "system"
}
