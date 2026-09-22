package com.github.kr328.clash.core

import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.core.util.parseInetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.net.InetSocketAddress

private val boundaryFailure = ClashException("out of memory at the core boundary")

internal fun CompletableDeferred<Unit>.completeFetchResult(error: String?) {
    val closed = runCatching {
        if (error != null)
            completeExceptionally(ClashException(error))
        else
            complete(Unit)
    }.isSuccess

    if (!closed) {
        runCatching { completeExceptionally(boundaryFailure) }
    }
}

object Clash {
    enum class OverrideSlot {
        Persist, Session
    }

    // Порядок — числа исхода из ядра. Failed — нулевое значение: его же мост
    // отдаёт, когда вызов в ядре упал; запомненный выбор при нём не трогают.
    enum class PatchResult {
        Failed, Done, NoSelector
    }

    internal val CoreJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun reset() {
        Bridge.nativeReset()
    }

    fun forceGc() {
        Bridge.nativeForceGc()
    }

    // Intentionally a no-op in the core (tunnel/suspend.go): the tunnel keeps
    // running while the screen is off; the bridge entry is kept as is.
    fun suspendCore(suspended: Boolean) {
        Bridge.nativeSuspend(suspended)
    }

    fun queryTunnelState(): TunnelState {
        val json = Bridge.nativeQueryTunnelState()

        return CoreJson.decodeFromString(TunnelState.serializer(), json)
    }

    fun queryTrafficNow(): Traffic {
        return Bridge.nativeQueryTrafficNow()
    }

    fun queryTrafficTotal(): Traffic {
        return Bridge.nativeQueryTrafficTotal()
    }

    fun notifyDnsChanged(dns: List<String>) {
        Bridge.nativeNotifyDnsChanged(dns.toSet().joinToString(separator = ","))
    }

    fun notifyTimeZoneChanged(name: String, offset: Int) {
        Bridge.nativeNotifyTimeZoneChanged(name, offset)
    }

    fun notifyInstalledAppsChanged(uids: List<Pair<Int, String>>) {
        val uidList = uids.joinToString(separator = ",") { "${it.first}:${it.second}" }

        Bridge.nativeNotifyInstalledAppChanged(uidList)
    }

    fun startTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        markSocket: (Int) -> Boolean,
        querySocketUid: (protocol: Int, source: InetSocketAddress, target: InetSocketAddress) -> Int
    ) {
        val code = Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                markSocket(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                // A nil net.Addr on the Go side arrives as "<nil>"; an exception
                // here would stay pending across the JNI boundary, so report unknown
                if (source.isEmpty() || source == "<nil>" || target.isEmpty() || target == "<nil>") {
                    return -1
                }

                val src = runCatching { parseInetSocketAddress(source) }.getOrNull() ?: return -1
                val dst = runCatching { parseInetSocketAddress(target) }.getOrNull() ?: return -1

                return querySocketUid(protocol, src, dst)
            }
        })

        if (code != 0) {
            throw ClashException("start tun failed")
        }
    }

    fun stopTun() {
        Bridge.nativeStopTun()
    }

    fun startHttp(listenAt: String): String? {
        return Bridge.nativeStartHttp(listenAt)
    }

    fun stopHttp() {
        Bridge.nativeStopHttp()
    }

    fun queryGroupNames(excludeNotSelectable: Boolean): ProxyGroupNames {
        return Bridge.nativeQueryGroupNames(excludeNotSelectable)?.let(::decodeGroupNames)
            ?: ProxyGroupNames()
    }

    internal fun decodeGroupNames(json: String): ProxyGroupNames {
        return CoreJson.decodeFromString(ProxyGroupNames.serializer(), json)
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        return Bridge.nativeQueryGroup(name, sort.name)
            ?.let { CoreJson.decodeFromString(ProxyGroup.serializer(), it) }
            ?: ProxyGroup("Unknown", emptyList(), "")
    }

    fun healthCheck(name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheck(this, name)
        }
    }

    @Serializable
    private class HealthCheckRequest(val groups: List<String>, val exclude: List<String>, val force: Boolean)

    fun healthCheckGroups(groups: List<String>, exclude: List<String>, force: Boolean): CompletableDeferred<Unit> {
        val request = CoreJson.encodeToString(HealthCheckRequest.serializer(), HealthCheckRequest(groups, exclude, force))

        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheckGroups(this, request)
        }
    }

    fun notifyNetworkChanged(closeConnections: Boolean, holdProbes: Boolean) {
        Bridge.nativeNotifyNetworkChanged(closeConnections, holdProbes)
    }

    fun probeCurrentNodes() {
        Bridge.nativeProbeCurrentNodes()
    }

    fun recoverDeadNodes(force: Boolean) {
        Bridge.nativeRecoverDeadNodes(force)
    }

    fun notifyNetworkReady() {
        Bridge.nativeNotifyNetworkReady()
    }

    fun setDeviceInfo(hwid: String, os: String, osVersion: String, model: String) {
        Bridge.nativeSetDeviceInfo(hwid, os, osVersion, model)
    }

    fun testProfileDelays(path: File): String {
        return Bridge.nativeTestProfileDelays(path.absolutePath) ?: "{}"
    }

    fun patchSelector(selector: String, name: String): PatchResult {
        return PatchResult.entries.getOrElse(Bridge.nativePatchSelector(selector, name)) {
            PatchResult.Failed
        }
    }

    fun setSecureChannel(enabled: Boolean) {
        Bridge.nativeSetSecureChannel(enabled)
    }

    fun fetchAndValid(
        path: File,
        url: String,
        force: Boolean,
        probe: Boolean,
        reportStatus: (FetchStatus) -> Unit
    ): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeFetchAndValid(
                object : FetchCallback {
                    override fun report(statusJson: String) {
                        reportStatus(
                            CoreJson.decodeFromString(
                                FetchStatus.serializer(),
                                statusJson
                            )
                        )
                    }

                    override fun complete(error: String?) {
                        this@apply.completeFetchResult(error)
                    }
                },
                path.absolutePath,
                url,
                force,
                probe
            )
        }
    }

    fun load(path: File): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeLoad(this, path.absolutePath)
        }
    }

    fun queryProviders(): List<Provider> {
        val json = Bridge.nativeQueryProviders() ?: return emptyList()

        val providers = CoreJson.decodeFromString(JsonArray.serializer(), json)

        return List(providers.size) {
            CoreJson.decodeFromJsonElement(Provider.serializer(), providers[it])
        }
    }

    fun updateProvider(type: Provider.Type, name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeUpdateProvider(this, type.toString(), name)
        }
    }

    fun queryOverride(slot: OverrideSlot): ConfigurationOverride {
        return try {
            CoreJson.decodeFromString(
                ConfigurationOverride.serializer(),
                Bridge.nativeReadOverride(slot.ordinal)
            )
        } catch (e: Exception) {
            ConfigurationOverride()
        }
    }

    fun patchOverride(slot: OverrideSlot, configuration: ConfigurationOverride) {
        Bridge.nativeWriteOverride(
            slot.ordinal,
            CoreJson.encodeToString(
                ConfigurationOverride.serializer(),
                configuration
            )
        )
    }

    fun queryModeOf(path: File, session: ConfigurationOverride): ProfileMode {
        return Bridge.nativeQueryModeOf(
            path.absolutePath,
            CoreJson.encodeToString(ConfigurationOverride.serializer(), session),
        )?.let(::decodeProfileMode) ?: ProfileMode()
    }

    internal fun decodeProfileMode(json: String): ProfileMode {
        return CoreJson.decodeFromString(ProfileMode.serializer(), json)
    }

    fun clearOverride(slot: OverrideSlot) {
        Bridge.nativeClearOverride(slot.ordinal)
    }

    fun reloadGeoData() {
        Bridge.nativeReloadGeoData()
    }

    fun subscribeLogcat(): ReceiveChannel<LogMessage> {
        return Channel<LogMessage>(32).apply {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                // Исключение отсюда — единственный выход горутины подписки в
                // ядре: по нему она снимает подписку и отпускает этот объект
                @OptIn(DelicateCoroutinesApi::class)
                override fun received(jsonPayload: String) {
                    check(!isClosedForSend) { "logcat channel closed" }

                    trySend(CoreJson.decodeFromString(LogMessage.serializer(), jsonPayload))
                }
            })
        }
    }
}
