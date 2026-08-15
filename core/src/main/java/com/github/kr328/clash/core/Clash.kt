package com.github.kr328.clash.core

import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.core.util.parseInetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

@kotlinx.serialization.Serializable
enum class DiagnosticsRuntimeState {
    CONNECTING,
    READY,
    CONFIGURATION_ERROR,
    ACCESS_DENIED,
    UNREACHABLE,
}

@kotlinx.serialization.Serializable
data class DiagnosticsStatus(
    val state: DiagnosticsRuntimeState,
)

private const val CONTROLLER_CONFIGURATION_SUCCEEDED = 0
private const val CONTROLLER_CONFIGURATION_ENTROPY_UNAVAILABLE = 1
private const val CONTROLLER_CONFIGURATION_SECRET_INVALID = 2

object Clash {
    enum class OverrideSlot {
        Persist, Session
    }

    private val CoreJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun reset() {
        Bridge.nativeReset()
    }

    fun startDiagnostics(endpoint: String, access: DiagnosticsAccess) {
        Bridge.nativeStartDiagnostics(
            endpoint,
            access.tunnelAuth,
            access.controllerSecret,
            access.remotePort,
        )
    }

    fun stopDiagnostics() {
        Bridge.nativeStopDiagnostics()
    }

    fun queryDiagnostics(): DiagnosticsStatus {
        return CoreJson.decodeFromString(DiagnosticsStatus.serializer(), Bridge.nativeQueryDiagnostics())
    }

    fun configureExternalController(access: ExternalControllerAccess) {
        val result = when (access) {
            ExternalControllerAccess.LocalOnly -> Bridge.nativeUseLocalControllerAccess()
            is ExternalControllerAccess.Diagnostics ->
                Bridge.nativeUseDiagnosticsControllerAccess(access.secret)
        }
        when (result) {
            CONTROLLER_CONFIGURATION_SUCCEEDED -> Unit
            CONTROLLER_CONFIGURATION_ENTROPY_UNAVAILABLE ->
                error("failed to generate external controller secret")
            CONTROLLER_CONFIGURATION_SECRET_INVALID ->
                error("external controller secret was rejected")
            else -> error("unknown external controller configuration result: $result")
        }
    }

    fun forceGc() {
        Bridge.nativeForceGc()
    }

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
                return querySocketUid(
                    protocol,
                    parseInetSocketAddress(source),
                    parseInetSocketAddress(target)
                )
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

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        val names = CoreJson.decodeFromString(
            JsonArray.serializer(),
            Bridge.nativeQueryGroupNames(excludeNotSelectable)
        )

        return names.map {
            require(it.jsonPrimitive.isString)

            it.jsonPrimitive.content
        }
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

    fun healthCheckAll() {
        Bridge.nativeHealthCheckAll()
    }

    fun notifyNetworkChanged(closeConnections: Boolean) {
        Bridge.nativeNotifyNetworkChanged(closeConnections)
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

    fun patchSelector(selector: String, name: String): Boolean {
        return Bridge.nativePatchSelector(selector, name)
    }

    fun setSecureChannel(enabled: Boolean) {
        Bridge.nativeSetSecureChannel(enabled)
    }

    fun fetchAndValid(
        path: File,
        url: String,
        force: Boolean,
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
                        if (error != null)
                            completeExceptionally(ClashException(error))
                        else
                            complete(Unit)
                    }
                },
                path.absolutePath,
                url,
                force
            )
        }
    }

    fun load(path: File): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeLoad(this, path.absolutePath)
        }
    }

    fun queryProviders(): List<Provider> {
        val providers =
            CoreJson.decodeFromString(JsonArray.serializer(), Bridge.nativeQueryProviders())

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

    fun clearOverride(slot: OverrideSlot) {
        Bridge.nativeClearOverride(slot.ordinal)
    }

    fun queryConfiguration(): UiConfiguration {
        return CoreJson.decodeFromString(
            UiConfiguration.serializer(),
            Bridge.nativeQueryConfiguration()
        )
    }

    fun subscribeLogcat(): ReceiveChannel<LogMessage> {
        return Channel<LogMessage>(32).apply {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    trySend(CoreJson.decodeFromString(LogMessage.serializer(), jsonPayload))
                }
            })
        }
    }

    fun setAgeSecretKey(key: String?) {
        Bridge.nativeSetAgeSecretKey(key)
    }

    fun genX25519KeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenX25519KeyPair()))
    }

    fun genHybridKeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenHybridKeyPair()))
    }

    fun veritySecretKeys(secretKey: String): Boolean {
        return Bridge.nativeVeritySecretKeys(secretKey)
    }

    fun toPublicKeys(secretKey: String): List<String> {
        return Bridge.nativeToPublicKeys(secretKey)
            ?.let { CoreJson.decodeFromString(ListSerializer(String.serializer()), it) }
            ?: emptyList()
    }

    fun verityPublicKeys(publicKey: String): Boolean {
        return Bridge.nativeVerityPublicKeys(publicKey)
    }

    private fun parseAgeKeyPair(value: String): AgeKeyPair {
        return CoreJson.decodeFromString(AgeKeyPair.serializer(), value)
    }
}
