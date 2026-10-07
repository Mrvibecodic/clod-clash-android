package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.service.model.PanelInfo
import java.util.UUID

private const val TITLE_MAX_CHARS = 60

internal fun truncateTitle(value: String): String {
    if (value.codePointCount(0, value.length) <= TITLE_MAX_CHARS) return value

    return value.substring(0, value.offsetByCodePoints(0, TITLE_MAX_CHARS)).trim() + "…"
}

// Заголовок режется и здесь: panel.json прежних версий мог сохранить длинный.
fun Context.readPanelInfo(uuid: UUID): PanelInfo? =
    readProfileJson(uuid, "panel.json", PanelInfo.serializer())?.let { it.copy(title = truncateTitle(it.title)) }

fun Context.profileLogoFile(uuid: UUID, panel: PanelInfo?): String? {
    val name = panel?.logoFile?.takeIf { it.isNotBlank() } ?: return null

    if (name.contains('/') || name.contains('\\') || name.contains("..")) return null

    val file = importedDir.resolve(uuid.toString()).resolve(name)

    return file.takeIf { it.isFile }?.absolutePath
}

fun profileDisplayName(panel: PanelInfo?, name: String, nameManual: Boolean): String {
    val title = panel?.title?.takeIf { it.isNotBlank() } ?: return name

    return if (nameManual && name != title) "$name ($title)" else title
}

fun Context.displayProfileName(uuid: UUID, name: String, nameManual: Boolean): String {
    return profileDisplayName(readPanelInfo(uuid), name, nameManual)
}
