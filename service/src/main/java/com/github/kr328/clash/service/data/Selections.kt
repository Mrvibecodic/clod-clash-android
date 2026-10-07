package com.github.kr328.clash.service.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex

object Selections {
    @OptIn(ExperimentalCoroutinesApi::class)
    val queue = Dispatchers.IO.limitedParallelism(1)

    // Очередь отдаёт поток на время запросов к базе; возврат выборов после
    // загрузки и новый выбор не должны перемежаться — иначе возврат применил бы
    // прочитанный до нового выбора узел поверх него
    val lock = Mutex()
}
