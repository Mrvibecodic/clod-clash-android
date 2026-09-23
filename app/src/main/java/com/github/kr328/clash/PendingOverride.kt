package com.github.kr328.clash

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.util.withClash
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
