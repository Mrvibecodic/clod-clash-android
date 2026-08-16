package com.github.kr328.clash.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PREFERENCES = "clod_subscription_groups"

private fun Context.groupsPreferences() =
    getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

suspend fun Context.querySubscriptionGroups(): Map<UUID, String> = withContext(Dispatchers.IO) {
    groupsPreferences().all.mapNotNull { (key, value) ->
        val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@mapNotNull null
        val name = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

        uuid to name
    }.toMap()
}

suspend fun Context.patchSubscriptionGroup(uuid: UUID, name: String?) {
    withContext(Dispatchers.IO) {
        groupsPreferences().edit().apply {
            if (name.isNullOrBlank()) {
                remove(uuid.toString())
            } else {
                putString(uuid.toString(), name.trim())
            }
        }.apply()
    }
}
