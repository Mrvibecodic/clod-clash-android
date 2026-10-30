package com.github.kr328.clash.common.util

import android.content.Context
import java.io.File
import java.io.IOException

val Context.clashDir: File
    get() = filesDir.resolve("clash")

// Пишет во временный файл и подменяет им этот: читатель видит либо прежнее
// содержимое, либо новое целиком. Не вышло — исключение; возврат из write —
// без замены. В обоих случаях прежний файл на месте, временный удалён
inline fun File.writeAtomically(temp: File = File("$path.tmp"), write: (File) -> Unit) {
    try {
        write(temp)

        if (!temp.renameTo(this)) throw IOException("Replace $this with $temp failed")
    } finally {
        temp.delete()
    }
}
