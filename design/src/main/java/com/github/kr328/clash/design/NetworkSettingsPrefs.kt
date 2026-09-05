package com.github.kr328.clash.design

import com.github.kr328.clash.service.store.ServiceStore

data class NetworkSettingsPrefs(
    val bypassPrivateNetwork: Boolean,
    val dnsHijacking: Boolean,
    val allowBypass: Boolean,
    val allowIpv6: Boolean,
    val systemProxy: Boolean,
    val tunStackMode: String,
    val resetConnections: Boolean,
    val keepAwake: Boolean,
) {
    companion object {
        fun read(store: ServiceStore): NetworkSettingsPrefs = NetworkSettingsPrefs(
            bypassPrivateNetwork = store.bypassPrivateNetwork,
            dnsHijacking = store.dnsHijacking,
            allowBypass = store.allowBypass,
            allowIpv6 = store.allowIpv6,
            systemProxy = store.systemProxy,
            tunStackMode = store.tunStackMode,
            resetConnections = store.resetConnectionsOnNetworkChange,
            keepAwake = store.keepAwake,
        )
    }
}
