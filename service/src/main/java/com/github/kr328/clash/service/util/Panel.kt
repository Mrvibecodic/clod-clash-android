package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.PanelInfo
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Чтение `panel.json` из каталога профиля.
 *
 * Файл пишет ядро при загрузке и обновлении подписки (`native/config/panel.go`),
 * лежит он в `filesDir`, а он один на все процессы пакета — значит и служба
 * в `:background`, и приложение читают одно и то же, без похода в IPC.
 */
private val json = Json { ignoreUnknownKeys = true }

fun Context.readPanelInfo(uuid: UUID): PanelInfo? {
    val file = importedDir.resolve(uuid.toString()).resolve("panel.json")

    if (!file.isFile) return null

    return try {
        // Сериализатор передаётся явно: без него компилятор выбирает перегрузку
        // с DeserializationStrategy и код не собирается.
        json.decodeFromString(PanelInfo.serializer(), file.readText())
    } catch (e: Exception) {
        Log.w("Read panel.json of $uuid: $e", e)

        null
    }
}

/**
 * Абсолютный путь к скачанному логотипу провайдера или `null`.
 *
 * Картинку кладёт ядро рядом с `config.yaml` при обновлении подписки
 * (`native/config/logo.go`), в `panel.json` попадает только имя файла:
 * каталог профиля приложение и так знает, а путь пережил бы переезд каталога
 * только на бумаге.
 */
fun Context.profileLogoFile(uuid: UUID, panel: PanelInfo?): String? {
    val name = panel?.logoFile?.takeIf { it.isNotBlank() } ?: return null

    // Имя приходит из файла, который писало ядро, но проверить его дешевле,
    // чем однажды прочитать по нему что-нибудь из соседнего каталога.
    if (name.contains('/') || name.contains('\\') || name.contains("..")) return null

    val file = importedDir.resolve(uuid.toString()).resolve(name)

    return file.takeIf { it.isFile }?.absolutePath
}

/**
 * Название подписки в том виде, в каком его надо показывать человеку.
 *
 * Панель присылает его заголовком `profile-title`, и это то же название,
 * что видно в списке подписок. Имя из базы — запасной вариант: там лежит либо
 * то, что человек вписал руками, либо «Новый профиль», поставленный при
 * добавлении по ссылке, когда о названии ещё ничего не известно.
 */
fun Context.displayProfileName(uuid: UUID, fallback: String): String {
    return readPanelInfo(uuid)?.title?.takeIf { it.isNotBlank() } ?: fallback
}
