package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.LogMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.Date

object ServiceLog {
    private const val CAPACITY = 64

    val events = Channel<LogMessage>(CAPACITY, BufferOverflow.DROP_OLDEST)

    fun mark(message: String) {
        events.trySend(LogMessage(LogMessage.Level.Info, "[APP] $message", Date()))
    }
}
