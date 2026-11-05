package com.github.kr328.clash.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Без буфера: пока тик не забрали, отсчёт стоит — свёрнутый экран и погашенный
// дисплей не будят процесс, а первый тик после паузы приходит сразу
fun CoroutineScope.ticker(period: Long): ReceiveChannel<Unit> {
    val channel = Channel<Unit>(Channel.RENDEZVOUS)

    launch {
        while (isActive) {
            channel.send(Unit)

            delay(period)
        }
    }

    return channel
}
