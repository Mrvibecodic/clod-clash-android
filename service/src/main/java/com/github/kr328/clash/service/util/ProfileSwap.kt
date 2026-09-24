package com.github.kr328.clash.service.util

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

object ProfileSwap {
    const val STALE_SUFFIX = ".old"

    const val CONFIG_FILE = "config.yaml"

    val OWN_FILES = listOf("alerts.json", "migration.json")

    enum class Step { KEEP_OWN_FILES, PARK_LIVE, PROMOTE_FRESH, DROP_STALE }

    sealed class Repair(val name: String) {
        class Restored(name: String) : Repair(name)
        class Dropped(name: String) : Repair(name)
    }

    fun replace(
        live: File,
        fresh: File,
        warn: (String) -> Unit = {},
        beforeStep: (Step) -> Unit = {},
    ) {
        require(isWhole(fresh)) { "fresh profile directory is not complete: $fresh" }

        val stale = staleOf(live)

        if (isWhole(stale) && !isWhole(live)) {
            live.deleteRecursively()

            if (!stale.renameTo(live)) throw IOException("cannot restore $stale")
        }

        beforeStep(Step.KEEP_OWN_FILES)
        keepOwnFiles(live, fresh, warn)

        // Переименования ниже журнал файловой системы сохраняет по порядку, а содержимое файлов —
        // нет: без fsync потеря питания сразу после подмены оставила бы пустой config.yaml.
        syncFiles(fresh)

        live.parentFile?.mkdirs()

        if (stale.exists() && !stale.deleteRecursively()) {
            throw IOException("cannot remove stale $stale")
        }

        beforeStep(Step.PARK_LIVE)
        if (live.exists() && !live.renameTo(stale)) {
            throw IOException("cannot park $live")
        }

        beforeStep(Step.PROMOTE_FRESH)
        if (!promote(fresh, live)) {
            if (isWhole(stale)) {
                live.deleteRecursively()
                stale.renameTo(live)
            }

            throw IOException("cannot promote $fresh")
        }

        beforeStep(Step.DROP_STALE)
        stale.deleteRecursively()
    }

    fun repair(importedDir: File): List<Repair> {
        val entries = importedDir.listFiles() ?: return emptyList()

        return entries
            .filter { it.name.endsWith(STALE_SUFFIX) }
            .mapNotNull { stale ->
                val live = importedDir.resolve(stale.name.removeSuffix(STALE_SUFFIX))

                when {
                    !isWhole(stale) -> {
                        stale.deleteRecursively()

                        null
                    }

                    isWhole(live) -> {
                        stale.deleteRecursively()

                        Repair.Dropped(live.name)
                    }

                    else -> {
                        live.deleteRecursively()

                        if (!stale.renameTo(live)) throw IOException("cannot restore $stale")

                        Repair.Restored(live.name)
                    }
                }
            }
    }

    // Читатели без profileLock. Живой каталог не целый, а .old целый — подмена идёт или оборвалась,
    // и ответ даёт только .old (то же решает repair). Ответ «файла нет» по живому каталогу даётся,
    // когда .old не было ни до, ни после чтения, и подтверждается повторным чтением: две подмены
    // подряд между ними не уместятся — их разделяет загрузка подписки. То же правило —
    // ReadProfileFile в native/config/panel/profilefile.go.
    fun <T> read(live: File, name: String, parse: (File) -> T): T? {
        val stale = staleOf(live)

        repeat(READ_ATTEMPTS) {
            val quiet = !stale.exists()

            if (!isWhole(live) && isWhole(stale)) {
                attempt(stale.resolve(name), parse)?.let { return it.value }

                if (isWhole(stale) && !isWhole(live)) return null
            }

            attempt(live.resolve(name), parse)?.let { return it.value }

            if (quiet && !stale.exists() && live.isDirectory) {
                return attempt(live.resolve(name), parse)?.value
            }
        }

        return null
    }

    private const val READ_ATTEMPTS = 3

    private class Found<T>(val value: T)

    private fun <T> attempt(file: File, parse: (File) -> T): Found<T>? =
        try {
            if (file.isFile) Found(parse(file)) else null
        } catch (e: FileNotFoundException) {
            null
        }

    fun staleOf(live: File): File = live.resolveSibling(live.name + STALE_SUFFIX)

    fun isWhole(dir: File): Boolean = dir.isDirectory && dir.resolve(CONFIG_FILE).isFile

    private fun promote(fresh: File, live: File): Boolean {
        if (fresh.renameTo(live)) return true

        if (live.exists() && !isWhole(live) && live.deleteRecursively()) {
            return fresh.renameTo(live)
        }

        return false
    }

    private fun syncFiles(dir: File) {
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            FileInputStream(file).use { it.fd.sync() }
        }
    }

    private fun keepOwnFiles(live: File, fresh: File, warn: (String) -> Unit) {
        for (name in OWN_FILES) {
            val own = live.resolve(name).takeIf { it.isFile } ?: continue

            try {
                own.copyTo(fresh.resolve(name), overwrite = true)
            } catch (e: IOException) {
                warn("Keep $name of ${live.name}: $e")
            }
        }
    }
}
