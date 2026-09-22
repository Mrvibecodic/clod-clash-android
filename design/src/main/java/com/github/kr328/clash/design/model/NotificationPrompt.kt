package com.github.kr328.clash.design.model

import java.util.concurrent.TimeUnit

const val NOTIFICATION_PROMPT_MAX_SNOOZES = 2

val NOTIFICATION_PROMPT_SNOOZE_MILLIS = TimeUnit.DAYS.toMillis(7)

fun notificationPromptDue(
    granted: Boolean,
    requested: Boolean,
    snoozedAt: Long,
    snoozes: Int,
    now: Long,
): Boolean = !granted &&
        !requested &&
        snoozes < NOTIFICATION_PROMPT_MAX_SNOOZES &&
        now - snoozedAt >= NOTIFICATION_PROMPT_SNOOZE_MILLIS
