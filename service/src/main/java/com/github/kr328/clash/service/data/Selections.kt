package com.github.kr328.clash.service.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

object Selections {
    @OptIn(ExperimentalCoroutinesApi::class)
    val queue = Dispatchers.IO.limitedParallelism(1)
}
