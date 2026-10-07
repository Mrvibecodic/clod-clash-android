package com.github.kr328.clash.design.util

import com.github.kr328.clash.common.util.PatternFileName
import com.github.kr328.clash.design.compose.screen.MIN_INTERVAL_MINUTES

typealias Validator = (String) -> Boolean

val ValidatorFileName: Validator = {
    PatternFileName.matches(it) && it.isNotBlank()
}

val ValidatorHttpUrl: Validator = {
    it.startsWith("https://", ignoreCase = true)
}

val ValidatorAutoUpdateInterval: Validator = {
    it.isEmpty() || (it.toLongOrNull() ?: 0) >= MIN_INTERVAL_MINUTES
}
