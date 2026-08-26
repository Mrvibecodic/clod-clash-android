package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.model.DiagnosticsLogEvent
import com.github.kr328.clash.service.PreferenceProvider
import java.util.UUID

internal data class PendingDiagnosticsEvent(
    val key: String,
    val recordedAt: Long,
    val event: DiagnosticsLogEvent,
)

internal fun decodeDiagnosticsEvents(values: Map<String, *>): List<PendingDiagnosticsEvent> =
    values.mapNotNull { (key, raw) ->
        if (!key.startsWith(EVENT_KEY_PREFIX)) return@mapNotNull null
        val parts = (raw as? String)?.split(',', limit = 2) ?: return@mapNotNull null
        val recordedAt = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        PendingDiagnosticsEvent(key, recordedAt, DiagnosticsLogEvent.fromCode(code))
    }.sortedWith(compareBy(PendingDiagnosticsEvent::recordedAt, PendingDiagnosticsEvent::key))

internal fun retainedDiagnosticsEvents(values: Map<String, *>): List<PendingDiagnosticsEvent> =
    decodeDiagnosticsEvents(values).takeLast(MAX_EVENTS)

class DiagnosticsEventJournal(context: Context) {
    private val preferences = PreferenceProvider.createSharedPreferencesFromContext(context)

    fun append(event: DiagnosticsLogEvent): Boolean {
        val key = EVENT_KEY_PREFIX + UUID.randomUUID()
        val value = "${System.currentTimeMillis()},${event.code}"
        val saved = preferences.edit().putString(key, value).commit()
        if (saved) prune()
        Log.i("[Diagnostics] event=${event.wireName} result=${if (saved) "queued" else "queue_failed"}")
        return saved
    }

    fun drain(): List<DiagnosticsLogEvent> {
        val entries = decodeDiagnosticsEvents(preferences.all)
        if (entries.isEmpty()) return emptyList()
        val editor = preferences.edit()
        entries.forEach { editor.remove(it.key) }
        if (!editor.commit()) return emptyList()
        return entries.map(PendingDiagnosticsEvent::event)
    }

    private fun prune() {
        val expired = decodeDiagnosticsEvents(preferences.all).dropLast(MAX_EVENTS)
        if (expired.isEmpty()) return
        val editor = preferences.edit()
        expired.forEach { editor.remove(it.key) }
        editor.commit()
    }
}

private const val EVENT_KEY_PREFIX = "diagnostics_pending_log_event_"
private const val MAX_EVENTS = 32
