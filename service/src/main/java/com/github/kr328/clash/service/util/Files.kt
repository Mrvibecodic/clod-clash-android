package com.github.kr328.clash.service.util

import android.content.Context
import java.io.File

val Context.importedDir: File
    get() = filesDir.resolve("imported")

val Context.pendingDir: File
    get() = filesDir.resolve("pending")

val Context.processingDir: File
    get() = filesDir.resolve("processing")

/**
 * Каталог пробной загрузки при переезде подписки на новый адрес.
 *
 * Отдельный от `processing`: проверка чужого адреса не должна трогать то,
 * что прямо сейчас применяется к рабочему профилю.
 */
val Context.migrationDir: File
    get() = filesDir.resolve("migration")

val File.directoryLastModified: Long?
    get() {
        return walk().map { it.lastModified() }.maxOrNull()
    }