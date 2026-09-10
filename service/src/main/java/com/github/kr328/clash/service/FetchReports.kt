package com.github.kr328.clash.service

import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.remote.IFetchObserver

internal class FetchReports(private var observer: IFetchObserver?) {
    private var info: FetchStatus? = null
    private val failed = ArrayList<String>()

    @Synchronized
    fun record(status: FetchStatus): Boolean {
        when (status.action) {
            FetchStatus.Action.SubscriptionInfo -> {
                info = status

                return false
            }
            FetchStatus.Action.ProviderFailed -> {
                val name = status.args.firstOrNull()

                if (name != null && !failed.contains(name)) {
                    failed.add(name)
                }
            }
            else -> Unit
        }

        return true
    }

    @Synchronized
    fun observer(): IFetchObserver? = observer

    @Synchronized
    fun drop() {
        observer = null
    }

    @Synchronized
    fun info(): FetchStatus? = info

    @Synchronized
    fun failed(): List<String> = failed.toList()
}
