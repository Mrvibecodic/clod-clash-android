package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.PanelInfo
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

fun Context.readPanelInfo(uuid: UUID): PanelInfo? {
    val file = importedDir.resolve(uuid.toString()).resolve("panel.json")

    if (!file.isFile) return null

    return try {
        json.decodeFromString(PanelInfo.serializer(), file.readText())
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

fun Context.displayProfileName(uuid: UUID, fallback: String): String {
    return readPanelInfo(uuid)?.title?.takeIf { it.isNotBlank() } ?: fallback
}
