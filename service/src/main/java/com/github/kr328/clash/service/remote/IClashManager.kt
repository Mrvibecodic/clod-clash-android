package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface
import java.util.UUID

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun queryTrafficTotal(): Long
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup
    fun queryConfiguration(): UiConfiguration
    fun queryProviders(): ProviderList

    fun patchSelector(group: String, name: String): Boolean

    /**
     * Запомнить выбор узла, пока ядро не поднято.
     *
     * `patchSelector` для этого не годится: он сначала спрашивает ядро,
     * а без загруженного профиля ядро откажет — и выбор не только не применится,
     * но и сотрётся из базы. Здесь запись идёт сразу в базу, а к ядру выбор
     * доедет при загрузке профиля: `ConfigurationModule` применяет сохранённые
     * выборы сразу после `Clash.load`.
     */
    fun rememberSelection(group: String, name: String)

    /** Сохранённый выбор узла в группе — чтобы показать его до подключения. */
    suspend fun querySelection(group: String): String?

    suspend fun healthCheck(group: String)

    /**
     * Измерить задержки узлов профиля, не поднимая ядро.
     *
     * Возвращает JSON «имя узла -> задержка в мс», `65535` у не ответивших.
     */
    suspend fun testProfileDelays(uuid: UUID): String
    suspend fun updateProvider(type: Provider.Type, name: String)

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun setLogObserver(observer: ILogObserver?)
}