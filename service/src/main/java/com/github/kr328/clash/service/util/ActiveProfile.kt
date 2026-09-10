package com.github.kr328.clash.service.util

import java.util.UUID

enum class ActiveProfileAction {
    Keep,
    Clear,
    Restore,
}

fun activeProfileGone(stored: UUID?, gone: UUID): ActiveProfileAction =
    if (stored == gone) ActiveProfileAction.Clear else ActiveProfileAction.Keep

fun activeProfileRollback(
    stored: UUID?,
    failed: UUID,
    retained: UUID?,
    retainedExists: Boolean,
): ActiveProfileAction =
    if (stored == failed && retained != null && retained != failed && retainedExists)
        ActiveProfileAction.Restore
    else
        ActiveProfileAction.Keep
