package com.github.kr328.clash.design.model

// Шаг защищённого канала при добавлении подписки или включении канала у неё:
// проверка, повтор, пока сервер молчит, или обычный путь, если канала нет.
sealed interface ChannelStage {
    data object Checking : ChannelStage
    data class Retry(val attempt: Int, val total: Int) : ChannelStage
    data object Plain : ChannelStage
}
