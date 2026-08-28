package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.model.DiagnosticsLogEvent
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.service.PreferenceProvider
import java.util.UUID

internal data class DiagnosticsModeCommand(
    val key: String,
    val recordedAt: Long,
    val mode: DiagnosticsMode,
    val event: DiagnosticsLogEvent?,
)

internal fun decodeDiagnosticsModeCommands(values: Map<String, *>): List<DiagnosticsModeCommand> =
    values.mapNotNull { (key, raw) ->
        if (!key.startsWith(MODE_KEY_PREFIX)) return@mapNotNull null
        val parts = (raw as? String)?.split(',', limit = 3) ?: return@mapNotNull null
        val recordedAt = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val mode = parts.getOrNull(1)?.let { value -> DiagnosticsMode.entries.firstOrNull { it.name == value } }
            ?: return@mapNotNull null
        val event = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it >= 0 }?.let(DiagnosticsLogEvent::fromCode)
        DiagnosticsModeCommand(key, recordedAt, mode, event)
    }.sortedWith(compareBy(DiagnosticsModeCommand::recordedAt, DiagnosticsModeCommand::key))

internal fun retainedDiagnosticsModeCommands(values: Map<String, *>): List<DiagnosticsModeCommand> =
    decodeDiagnosticsModeCommands(values).takeLast(MAX_MODE_COMMANDS)

internal class DiagnosticsModeCommandStore(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)

    fun append(mode: DiagnosticsMode, event: DiagnosticsLogEvent?): String? {
        val key = MODE_KEY_PREFIX + UUID.randomUUID()
        val value = "${System.currentTimeMillis()},${mode.name},${event?.code ?: -1}"
        if (!preferences.edit().putString(key, value).commit()) return null
        prune()
        return key
    }

    fun acknowledge(key: String): Boolean {
        if (!key.startsWith(MODE_KEY_PREFIX)) return false
        return preferences.edit().remove(key).commit()
    }

    fun drain(): List<DiagnosticsModeCommand> {
        val commands = decodeDiagnosticsModeCommands(preferences.all)
        if (commands.isEmpty()) return emptyList()
        val editor = preferences.edit()
        commands.forEach { editor.remove(it.key) }
        if (!editor.commit()) return emptyList()
        return commands
    }

    private fun prune() {
        val expired = decodeDiagnosticsModeCommands(preferences.all).dropLast(MAX_MODE_COMMANDS)
        if (expired.isEmpty()) return
        val editor = preferences.edit()
        expired.forEach { editor.remove(it.key) }
        editor.commit()
    }
}

private const val MODE_KEY_PREFIX = "diagnostics_pending_mode_"
private const val MAX_MODE_COMMANDS = 16
