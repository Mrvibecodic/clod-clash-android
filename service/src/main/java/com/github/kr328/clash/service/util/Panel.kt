package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.PanelInfo
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

private const val TITLE_MAX_CHARS = 60

internal fun truncateTitle(value: String): String {
    if (value.codePointCount(0, value.length) <= TITLE_MAX_CHARS) return value

    return value.substring(0, value.offsetByCodePoints(0, TITLE_MAX_CHARS)).trim() + "…"
}

fun Context.readPanelInfo(uuid: UUID): PanelInfo? {
    val file = importedDir.resolve(uuid.toString()).resolve("panel.json")

    if (!file.isFile) return null

    return try {
        json.decodeFromString(PanelInfo.serializer(), file.readText()).let {
            it.copy(title = truncateTitle(it.title))
        }
    } catch (e: Exception) {
        Log.w("Read panel.json of $uuid: $e", e)

        null
    }
}

fun Context.profileLogoFile(uuid: UUID, panel: PanelInfo?): String? {
    val name = panel?.logoFile?.takeIf { it.isNotBlank() } ?: return null

    if (name.contains('/') || name.contains('\\') || name.contains("..")) return null

    val file = importedDir.resolve(uuid.toString()).resolve(name)

    return file.takeIf { it.isFile }?.absolutePath
}

fun profileDisplayName(panel: PanelInfo?, name: String): String =
    panel?.title?.takeIf { it.isNotBlank() } ?: name

fun Context.displayProfileName(uuid: UUID, fallback: String): String {
    return profileDisplayName(readPanelInfo(uuid), fallback)
}
