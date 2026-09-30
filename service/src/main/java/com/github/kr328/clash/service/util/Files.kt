package com.github.kr328.clash.service.util

import android.content.Context
import java.io.File

val Context.importedDir: File
    get() = filesDir.resolve("imported")

val Context.pendingDir: File
    get() = filesDir.resolve("pending")

val Context.processingDir: File
    get() = filesDir.resolve("processing")

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
