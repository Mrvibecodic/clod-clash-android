package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface
import java.util.UUID

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun queryTrafficTotal(): Long
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): ProxyGroupNames
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup
    fun queryProviders(): ProviderList

    fun patchSelector(group: String, name: String): Boolean

    fun rememberSelection(group: String, name: String)

    suspend fun querySelections(): Map<String, String>

    suspend fun healthCheck(group: String)
    suspend fun healthCheckGroups(groups: List<String>, exclude: List<String>, force: Boolean)

    suspend fun testProfileDelays(uuid: UUID): String
    suspend fun updateProvider(type: Provider.Type, name: String)

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    suspend fun queryProfileMode(): ProfileMode
    suspend fun setProfileMode(mode: TunnelState.Mode?)

    fun reloadGeoData()

    fun setLogObserver(observer: ILogObserver?)
}
