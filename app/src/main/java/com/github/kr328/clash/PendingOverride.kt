package com.github.kr328.clash

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Несохранённые правки переопределения держатся в процессе: целиком в Bundle они не помещаются
// (большой список hosts не проходит через Binder), а признак «правки есть» лежит в Bundle
internal object PendingOverride {
    const val KEY = "pending_override"

    const val SLOT_OVERRIDE = "override"
    const val SLOT_META = "meta"

    private val slots = mutableMapOf<String, ConfigurationOverride>()

    fun put(slot: String, value: ConfigurationOverride?) {
        if (value == null) {
            slots.remove(slot)
        } else {
            slots[slot] = value
        }
    }

    fun take(slot: String): ConfigurationOverride? = slots.remove(slot)

    fun clear(slot: String) {
        slots.remove(slot)
    }

    fun clearAll() {
        slots.clear()
    }
}

internal suspend fun clearPersistedOverride() {
    withClash { clearOverride(Clash.OverrideSlot.Persist) }

    withContext(Dispatchers.Main) { PendingOverride.clearAll() }
}
