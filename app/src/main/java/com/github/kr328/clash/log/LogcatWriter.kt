package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class LogcatWriter(target: File, private val limit: Long = MAX_BYTES) : AutoCloseable {
    constructor(context: Context) : this(context.logsDir.resolve(LogFile.generate().fileName))

    private val output = FileOutputStream(target)

    private var written = 0L

    override fun close() {
        output.close()
    }

    fun appendMessage(message: LogMessage): Boolean {
        val line = encode(message)

        if (written + line.size > limit) return false

        write(line)

        return true
    }

    fun appendLast(message: LogMessage) {
        write(encode(message))
    }

    // Без буфера: запись логов включают ради падения, а буфер как раз падение и теряет.
    private fun write(line: ByteArray) {
        output.write(line)

        written += line.size
    }

    private fun encode(message: LogMessage): ByteArray =
        String.format(Locale.ROOT, FORMAT, message.time.time, message.level.name, message.message)
            .toByteArray()

    companion object {
        const val MAX_BYTES = 25L * 1024 * 1024

        private const val FORMAT = "%d:%s:%s\n"
    }
}
