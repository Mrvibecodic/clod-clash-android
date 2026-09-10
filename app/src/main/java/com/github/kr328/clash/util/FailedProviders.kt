package com.github.kr328.clash.util

import com.github.kr328.clash.core.model.FetchStatus
import java.util.concurrent.atomic.AtomicReference

object FailedProviders {
    fun accumulate(collected: AtomicReference<List<String>>, status: FetchStatus) {
        while (true) {
            val current = collected.get()
            val next = accumulate(current, status)

            if (next === current || collected.compareAndSet(current, next)) return
        }
    }

    fun accumulate(collected: List<String>, status: FetchStatus): List<String> {
        if (status.action != FetchStatus.Action.ProviderFailed) {
            return collected
        }

        val name = status.args.firstOrNull()?.takeIf { it.isNotBlank() } ?: return collected

        if (collected.contains(name)) {
            return collected
        }

        return collected + name
    }
}
