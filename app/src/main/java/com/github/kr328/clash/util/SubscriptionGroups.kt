package com.github.kr328.clash.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Группы подписок — способ разложить несколько подписок по полкам («Личные»,
 * «Работа»), как это сделано на десктопе.
 *
 * Хранится отдельным файлом настроек, а не полем в базе профилей: это чисто
 * пользовательская пометка, ядру и службе она не нужна, а колонка в Room стоила
 * бы миграции. Ключ — uuid профиля, значение — имя группы.
 */
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

/** Пустое имя означает «без группы» — запись просто удаляется. */
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
