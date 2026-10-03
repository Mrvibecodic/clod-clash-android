package com.github.kr328.clash.service.freeze

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

// clod:freeze — итоги проверки 16–20 подписки: files/freeze/<uuid>.json,
// уходит вместе с подпиской.
object FreezeStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun file(context: Context, uuid: UUID): File = context.filesDir.resolve("freeze").resolve("$uuid.json")

    fun load(context: Context, uuid: UUID): FreezeFile {
        val file = file(context, uuid)

        if (!file.isFile) return FreezeFile()

        return try {
            json.decodeFromString(FreezeFile.serializer(), file.readText())
        } catch (e: Exception) {
            Log.w("Freeze results of $uuid could not be read and start over: $e")

            FreezeFile()
        }
    }

    fun save(context: Context, uuid: UUID, data: FreezeFile) {
        val file = file(context, uuid)

        file.parentFile?.mkdirs()

        val fresh = File(file.path + ".new")

        fresh.writeText(json.encodeToString(FreezeFile.serializer(), data))

        if (!fresh.renameTo(file)) {
            file.delete()

            fresh.renameTo(file)
        }
    }

    fun delete(context: Context, uuid: UUID) {
        file(context, uuid).delete()
    }
}
