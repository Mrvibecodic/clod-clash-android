package com.github.kr328.clash.service.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import java.security.MessageDigest
import java.util.UUID

/**
 * Опознание устройства для лимитов панели (семейство `x-hwid`).
 *
 * Панель Remnawave считает устройства по этому идентификатору: показывает их
 * списком, ограничивает количество и отвечает заглушкой вместо конфигурации,
 * когда лимит исчерпан. Без него подписка с лимитом устройств ведёт себя
 * непредсказуемо — панель не знает, кому отдаёт конфигурацию.
 *
 * Считается из `Settings.Secure.ANDROID_ID` и хешируется: сырой идентификатор
 * устройство не покидает. Алгоритм тот же, что на десктопе, — SHA-256 от пары
 * «идентификатор + соль», первые 32 шестнадцатеричных знака. Remnawave >= 2.9
 * проверяет значение регуляркой `^[a-zA-Z0-9=-]{10,64}$`, и 32 знака в неё
 * укладываются.
 */
private const val HWID_SALT = "clod-clash"

private const val HWID_HEX_LEN = 32

/** Человекочитаемое название системы, уходит как `x-device-os`. */
const val DEVICE_OS = "Android"

/**
 * Версия системы, уходит как `x-ver-os`.
 *
 * Именно `RELEASE` («14»), а не номер сборки: панель показывает эту строку
 * человеку в списке устройств, и номер сборки там ничего не объясняет.
 */
fun deviceOsVersion(): String = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown"

/**
 * Описание устройства, уходит как `x-device-model`.
 *
 * Производитель и модель, а НЕ имя устройства: имя человек задаёт сам, и туда
 * попадают «Телефон Ивана» и прочее, чему в панели провайдера делать нечего.
 */
fun deviceModel(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()

    return when {
        model.isEmpty() -> manufacturer.ifEmpty { DEVICE_OS }
        // «Xiaomi Xiaomi 14» читается плохо: у части производителей модель уже
        // начинается с их же имени.
        model.startsWith(manufacturer, ignoreCase = true) -> model
        manufacturer.isEmpty() -> model
        else -> "$manufacturer $model"
    }
}

/**
 * Идентификатор устройства. `null` — опознание выключено человеком.
 *
 * Считается каждый раз заново, а НЕ сохраняется. Сохранённое значение уехало бы
 * на новый телефон вместе с автоматической резервной копией Android
 * (`allowBackup`), и два физических устройства заняли бы одно место в лимите —
 * ровно то, что эта затея должна предотвращать. Смысла в кеше и нет:
 * `ANDROID_ID` сам по себе стабилен, а вычисление — один SHA-256.
 *
 * Сохраняется только запасное случайное значение: `ANDROID_ID` пуст на части
 * прошивок, и без сохранения устройство меняло бы личность на каждый запрос.
 */
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

    store.hwid.takeIf { isValidHwid(it) }?.let { return it }

    val fallback = digest(UUID.randomUUID().toString())

    store.hwid = fallback

    return fallback
}

/**
 * Значение, годное для заголовка HTTP.
 *
 * Модель телефона попадает в заголовок как есть, а прошивки бывают всякие:
 * управляющий символ в значении заставляет клиент ядра отбить ВЕСЬ запрос
 * («invalid header field value»), и подписка перестанет обновляться совсем.
 * Лучше отправить обрезанное название устройства, чем не отправить ничего.
 */
private fun headerSafe(value: String): String {
    return value.asSequence()
        .filter { it.code in 0x20..0x7E }
        .joinToString("")
        .trim()
        .take(64)
}

/** Проверка Remnawave >= 2.9: `^[a-zA-Z0-9=-]{10,64}$`. */
fun isValidHwid(value: String): Boolean {
    return value.length in 10..64 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '=' || it == '-' }
}

private fun digest(raw: String): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .apply {
            update(raw.toByteArray())
            update(HWID_SALT.toByteArray())
        }
        .digest()

    return hash.joinToString("") { "%02x".format(it) }.take(HWID_HEX_LEN)
}

/**
 * Отдать ядру сведения об устройстве перед запросом подписки.
 *
 * Зовётся из процесса службы: ядро живёт там, и оно же собирает запрос.
 * Вызов дешёвый (значение из хранилища), поэтому обновляем перед каждым
 * запросом — иначе выключенное человеком опознание доехало бы до ядра
 * только после перезапуска службы.
 */
fun Context.applyDeviceInfo() {
    Clash.setDeviceInfo(
        hwid = deviceHwid().orEmpty(),
        os = DEVICE_OS,
        osVersion = headerSafe(deviceOsVersion()),
        model = headerSafe(deviceModel()),
    )
}
