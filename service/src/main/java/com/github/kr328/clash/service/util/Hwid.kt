package com.github.kr328.clash.service.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.sha256Hex
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import java.util.UUID

private const val HWID_SALT = "clod-clash"

private const val HWID_HEX_LEN = 32

const val DEVICE_OS = "Android"

fun deviceOsVersion(): String = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown"

fun deviceModel(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()

    return when {
        model.isEmpty() -> manufacturer.ifEmpty { DEVICE_OS }
        model.startsWith(manufacturer, ignoreCase = true) -> model
        manufacturer.isEmpty() -> model
        else -> "$manufacturer $model"
    }
}

@SuppressLint("HardwareIds")
fun Context.deviceHwid(): String? {
    val store = ServiceStore(this)

    if (!store.enableHwid) return null

    val raw = try {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Exception) {
        Log.w("Read ANDROID_ID: $e", e)

        null
    }?.trim()?.takeIf { it.isNotEmpty() }

    if (raw != null) return digest(raw)

    // Запасной HWID заводится один раз: загрузка подписки и отправка отчёта
    // спрашивают его независимо, и без замка одновременные завели бы два разных
    return synchronized(fallbackLock) {
        store.hwid.takeIf { isValidHwid(it) } ?: digest(UUID.randomUUID().toString()).also { store.hwid = it }
    }
}

private val fallbackLock = Any()

private fun headerSafe(value: String): String {
    return value.asSequence()
        .filter { it.code in 0x20..0x7E }
        .joinToString("")
        .trim()
        .take(64)
}

fun isValidHwid(value: String): Boolean {
    return value.length in 10..64 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '=' || it == '-' }
}

private fun digest(raw: String): String =
    sha256Hex(raw.toByteArray() + HWID_SALT.toByteArray()).take(HWID_HEX_LEN)

class DeviceCard(val hwid: String, val os: String, val osVersion: String, val model: String)

// Карточка устройства на этот момент: HWID пустой, если опознание выключено
fun Context.deviceCard(): DeviceCard {
    return DeviceCard(
        hwid = deviceHwid().orEmpty(),
        os = DEVICE_OS,
        osVersion = headerSafe(deviceOsVersion()),
        model = headerSafe(deviceModel()),
    )
}

fun Context.applyDeviceInfo() {
    val card = deviceCard()

    Clash.setDeviceInfo(
        hwid = card.hwid,
        os = card.os,
        osVersion = card.osVersion,
        model = card.model,
    )
}
