package com.github.kr328.clash.log

import android.content.Context
import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.design.model.LogFile
import com.github.kr328.clash.util.logsDir
import java.io.BufferedReader
import java.io.FileReader
import java.util.*

class LogcatReader(context: Context, file: LogFile) : AutoCloseable {
    private val reader = BufferedReader(FileReader(context.logsDir.resolve(file.fileName)))

    override fun close() {
        reader.close()
    }

    fun readAll(): List<LogMessage> {
        var lastTime = Date(0)
        return reader.lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("#") }
            .map { it.split(":", limit = 3) }
            .map {
                val time = it[0].toLongOrNull()?.let { Date(it) } ?: lastTime
                val level = it.getOrNull(1)
                    ?.let { name -> LogMessage.Level.entries.firstOrNull { l -> l.name == name } }
                val logMessage = if (it[0].toLongOrNull() != null && level != null && it.size >= 3) {
                    LogMessage(
                        time = time,
                        level = level,
                        message = it[2]
                    )
                } else {
                    LogMessage(
                        time = time,
                        level = LogMessage.Level.Warning,
                        message = it.joinToString(":")
                    )
                }
                lastTime = time
                logMessage
            }
            .toList()
    }
}
