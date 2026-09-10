package com.github.kr328.clash.design.model

enum class PendingRestore {
    UsePending,
    UseStoredAndWarn,
    UseStored,
}

fun pendingRestore(flagSet: Boolean, valuePresent: Boolean): PendingRestore = when {
    valuePresent -> PendingRestore.UsePending
    flagSet -> PendingRestore.UseStoredAndWarn
    else -> PendingRestore.UseStored
}
