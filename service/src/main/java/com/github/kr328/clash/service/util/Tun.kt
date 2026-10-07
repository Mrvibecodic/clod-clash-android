package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.service.model.TunPrefs
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID

fun Context.readTunPrefs(uuid: UUID): TunPrefs? =
    readProfileJson(uuid, "tun.json", TunPrefs.serializer())

fun Context.activeTunPrefs(): TunPrefs? {
    return ServiceStore(this).activeProfile?.let { readTunPrefs(it) }
}

// Набор совпадает с NormalizeTunStack в native/config/panel/tun.go;
// без явного выбора и без стека в подписке — MIPS, как у самого ядра
private val TUN_STACKS = setOf("system", "gvisor", "mixed", "mips")

private const val DEFAULT_TUN_STACK = "mips"

fun resolveTunStack(mode: String, fromProfile: String): String = when {
    mode in TUN_STACKS -> mode
    fromProfile in TUN_STACKS -> fromProfile
    else -> DEFAULT_TUN_STACK
}
