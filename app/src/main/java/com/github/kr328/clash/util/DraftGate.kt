package com.github.kr328.clash.util

object DraftGate {
    fun savesOnStop(canceled: Boolean, changed: Boolean, valid: Boolean, committing: Boolean): Boolean =
        !canceled && changed && valid && !committing

    fun closesOnServiceRecreated(committing: Boolean): Boolean = !committing
}
