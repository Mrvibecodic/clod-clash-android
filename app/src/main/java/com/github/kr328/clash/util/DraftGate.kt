package com.github.kr328.clash.util

object DraftGate {
    fun savesOnStop(canceled: Boolean, changed: Boolean, valid: Boolean, committing: Boolean): Boolean =
        !canceled && changed && valid && !committing

    fun exitsSilently(changed: Boolean, imported: Boolean, draft: Boolean): Boolean =
        !changed && !(imported && draft)

    fun closesOnServiceRecreated(committing: Boolean): Boolean = !committing
}
