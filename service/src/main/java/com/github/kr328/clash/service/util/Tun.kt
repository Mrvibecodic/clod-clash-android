package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.core.model.ProviderValues
import com.github.kr328.clash.service.model.TunPrefs
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

fun Context.readTunPrefs(uuid: UUID): TunPrefs? =
    readProfileJson(uuid, "tun.json", TunPrefs.serializer())

fun Context.activeTunPrefs(): TunPrefs? {
    return ServiceStore(this).activeProfile?.let { readTunPrefs(it) }
}

// Стек из tun.json уже нормализован ядром при разборе подписки (NormalizeTunStack
// в native/config/panel/tun.go); незнакомое значение мост сам заменяет на MIPS.
val TUN_STACKS = listOf("system", "gvisor", "mixed", "mips")

const val TUN_STACK_AUTO = "auto"

private const val DEFAULT_TUN_STACK = "mips"

fun resolveTunStack(mode: String, fromProfile: String): String {
    val stack = fromProfile.trim().lowercase()

    return when {
        mode in TUN_STACKS -> mode
        stack.isNotEmpty() -> stack
        else -> DEFAULT_TUN_STACK
    }
}

fun providerTunStack(mode: String, fromProfile: String): ProviderValues.Line? {
    if (mode !in TUN_STACKS || fromProfile.isBlank()) return null

    val now = resolveTunStack(mode, fromProfile)
    val becomes = resolveTunStack(TUN_STACK_AUTO, fromProfile)

    if (now == becomes) return null

    return ProviderValues.Line(
        ProviderValues.TUN_STACK,
        JsonPrimitive(now).toString(),
        JsonPrimitive(becomes).toString(),
        listOf(ProviderValues.TUN_STACK),
    )
}
