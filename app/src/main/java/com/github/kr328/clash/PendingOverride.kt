package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.NoticeKind
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.ServiceUnavailableException
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

// Несохранённые правки переопределения держатся в процессе: целиком в Bundle они не помещаются
// (большой список hosts не проходит через Binder), а признак «правки есть» лежит в Bundle
internal object PendingOverride {
    const val KEY = "pending_override"

    const val SLOT_OVERRIDE = "override"
    const val SLOT_META = "meta"

    class Draft(val value: ConfigurationOverride) {
        private var origin = snapshot(value)

        fun dirty(): Boolean = snapshot(value) != origin

        suspend fun save() {
            val snapshot = snapshot(value)

            if (snapshot == origin) return

            withClash { patchOverride(Clash.OverrideSlot.Persist, value) }

            origin = snapshot
        }
    }

    private val slots = mutableMapOf<String, Draft>()

    fun put(slot: String, draft: Draft?) {
        if (draft == null) {
            slots.remove(slot)
        } else {
            slots[slot] = draft
        }
    }

    fun take(slot: String): Draft? = slots.remove(slot)

    fun clear(slot: String) {
        slots.remove(slot)
    }

    fun clearAll() {
        slots.clear()
    }

    private fun snapshot(value: ConfigurationOverride): String =
        Json.encodeToString(ConfigurationOverride.serializer(), value)
}

internal suspend fun clearPersistedOverride() {
    withClash { clearOverride(Clash.OverrideSlot.Persist) }

    withContext(Dispatchers.Main) { PendingOverride.clearAll() }
}

// Нечитаемые настройки — не заводские: экран показывает причину, ничего не
// пишет и оставляет только сброс.
internal sealed interface StoredOverride {
    class Readable(val value: ConfigurationOverride) : StoredOverride
    class Unreadable(val reason: String) : StoredOverride
}

internal suspend fun readStoredOverride(): StoredOverride = try {
    StoredOverride.Readable(withClash { queryOverride(Clash.OverrideSlot.Persist) })
} catch (e: CancellationException) {
    throw e
} catch (e: ServiceUnavailableException) {
    throw e
} catch (e: Exception) {
    Log.w("Read override: $e", e)

    StoredOverride.Unreadable(Redact.text(e.message ?: e.javaClass.name))
}

// Неудачная запись не закрывает экран молча: правки остаются на нём, а в
// уведомлении — выход без сохранения, чтобы экран не держал человека, пока
// запись не проходит.
internal suspend fun PendingOverride.Draft.saveReporting(
    design: Design<*>,
    onDiscard: () -> Unit,
): Boolean = try {
    save()

    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("Save override: $e", e)

    design.showToast(
        R.string.clod_override_save_failed,
        ToastDuration.Long,
        actionLabel = R.string.clod_override_discard,
        onAction = onDiscard,
        kind = NoticeKind.Error,
    )

    false
}

internal suspend fun resetStoredOverride(design: Design<*>): Boolean = try {
    clearPersistedOverride()

    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("Reset override: $e", e)

    design.showExceptionToast(e, R.string.clod_override_reset_failed)

    false
}
