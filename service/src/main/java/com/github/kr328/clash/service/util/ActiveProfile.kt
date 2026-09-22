package com.github.kr328.clash.service.util

import java.util.UUID

enum class ActiveProfileAction {
    Keep,
    Clear,
    Restore,
    Set,
}

fun activeProfileGone(stored: UUID?, gone: UUID): ActiveProfileAction =
    if (stored == gone) ActiveProfileAction.Clear else ActiveProfileAction.Keep

fun activeProfileSelect(stored: UUID?, requested: UUID, exists: Boolean): ActiveProfileAction =
    if (exists && stored != requested) ActiveProfileAction.Set else ActiveProfileAction.Keep

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
