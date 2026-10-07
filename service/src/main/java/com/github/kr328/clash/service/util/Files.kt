package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

val Context.importedDir: File
    get() = filesDir.resolve("imported")

val Context.pendingDir: File
    get() = filesDir.resolve("pending")

val Context.processingDir: File
    get() = filesDir.resolve("processing")

/** Когда подписку загружали в последний раз — mtime её `config.yaml`; 0, если ни разу. */
fun Context.fetchedAt(uuid: UUID): Long =
    importedDir.resolve(uuid.toString()).resolve("config.yaml").lastModified()

private val profileJson = Json { ignoreUnknownKeys = true }

/** JSON-файл папки подписки (без замка подмены, см. [ProfileSwap.read]); не прочитался — null и запись в журнал. */
fun <T> Context.readProfileJson(uuid: UUID, name: String, serializer: DeserializationStrategy<T>): T? =
    try {
        ProfileSwap.read(importedDir.resolve(uuid.toString()), name) { file ->
            profileJson.decodeFromString(serializer, file.readText())
        }
    } catch (e: Exception) {
        Log.w("Read $name of $uuid: $e", e)

        null
    }

val File.directoryLastModified: Long?
    get() {
        return walk().map { it.lastModified() }.maxOrNull()
    }

// Копия каталога профиля с временем изменения файлов исходника: по нему ядро
// считает возраст наборов правил и провайдеров, и без него каждое обновление
// подписки делало бы их «только что скачанными» — с интервалом в сутки они
// не обновлялись бы никогда.
fun File.copyProfileTo(target: File, overwrite: Boolean = false) {
    copyRecursively(target, overwrite)

    walkTopDown().filter { it.isFile }.forEach {
        target.resolve(it.relativeTo(this)).setLastModified(it.lastModified())
    }
}
