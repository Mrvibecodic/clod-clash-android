package com.github.kr328.clash.common.util

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException

val Context.clashDir: File
    get() = filesDir.resolve("clash")

// Ставит готовый файл на место target, сперва записав его на диск: иначе
// внезапное отключение питания оставляет target пустым. false — не вышло.
fun File.moveDurablyTo(target: File): Boolean = try {
    FileInputStream(this).use { it.fd.sync() }

    renameTo(target)
} catch (e: IOException) {
    false
}

// Пишет во временный файл и подменяет им этот: читатель видит либо прежнее
// содержимое, либо новое целиком. Не вышло — исключение; возврат из write —
// без замены. В обоих случаях прежний файл на месте, временный удалён
inline fun File.writeAtomically(temp: File = File("$path.tmp"), write: (File) -> Unit) {
    try {
        write(temp)

        if (!temp.moveDurablyTo(this)) throw IOException("Replace $this with $temp failed")
    } finally {
        temp.delete()
    }
}
