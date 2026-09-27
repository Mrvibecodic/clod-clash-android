package com.github.kr328.clash.service.store

import com.github.kr328.clash.common.Global
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Только для процесса интерфейса. Там каждое обращение к ServiceStore — синхронный
// вызов в :background, который может ещё и поднять этот процесс. Все такие
// обращения идут через одну последовательную очередь: не с главного потока и
// строго в порядке записи, поэтому чтение всегда видит записи, сделанные до него.
// Хранилище — от applicationContext: ссылка на экран пережила бы его закрытие.
object ServiceSettings {
    private val queue = Dispatchers.IO.limitedParallelism(1)

    private val store by lazy { ServiceStore(Global.application) }

    fun write(block: ServiceStore.() -> Unit) {
        Global.launch(queue) { store.block() }
    }

    suspend fun <T> access(block: ServiceStore.() -> T): T = withContext(queue) { store.block() }
}
