package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Чтение `panel.json` из каталога профиля.
 *
 * Каталог профиля лежит в `filesDir` приложения, а он один на все процессы
 * одного пакета — служба пишет туда через ядро, приложение читает отсюда,
 * без лишнего похода в IPC.
 */
private val json = Json { ignoreUnknownKeys = true }

suspend fun Context.queryPanelInfo(uuid: UUID): PanelInfo? = withContext(Dispatchers.IO) {
    val file = importedDir.resolve(uuid.toString()).resolve("panel.json")

    if (!file.isFile) return@withContext null

    try {
        // Сериализатор передаётся явно: без него компилятор выбирает перегрузку
        // с DeserializationStrategy и код не собирается.
        json.decodeFromString(PanelInfo.serializer(), file.readText())
    } catch (e: Exception) {
        Log.w("Read panel.json of $uuid: $e", e)

        null
    }
}
