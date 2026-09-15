package com.github.kr328.clash.service.remote

import android.os.BadParcelableException
import android.os.NetworkOnMainThreadException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.ProviderList
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxyGroupNames
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.service.model.Profile
import java.util.UUID

private inline fun <T> guard(block: () -> T): T {
    try {
        return block()
    } catch (e: Throwable) {
        if (e is Exception) throw e

        throw RuntimeException(e.toString(), e)
    }
}

internal fun rethrownAsIs(e: Throwable): Boolean = when (e) {
    is SecurityException,
    is BadParcelableException,
    is IllegalArgumentException,
    is NullPointerException,
    is IllegalStateException,
    is NetworkOnMainThreadException,
    is UnsupportedOperationException,
    -> true

    else -> false
}

private inline fun <T> guardSync(name: String, block: () -> T): T {
    try {
        return block()
    } catch (e: Throwable) {
        if (rethrownAsIs(e)) throw e

        Log.w("Remote call $name failed: $e", e)

        throw IllegalStateException("$name: $e")
    }
}

class GuardedRemoteService(private val delegate: IRemoteService) : IRemoteService {
    override fun clash(): IClashManager = guardSync("clash") { delegate.clash() }

    override fun profile(): IProfileManager = guardSync("profile") { delegate.profile() }
}

class GuardedClashManager(private val delegate: IClashManager) : IClashManager by delegate {
    override fun queryTunnelState(): TunnelState =
        guardSync("queryTunnelState") { delegate.queryTunnelState() }

    override fun queryTrafficTotal(): Long =
        guardSync("queryTrafficTotal") { delegate.queryTrafficTotal() }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): ProxyGroupNames =
        guardSync("queryProxyGroupNames") { delegate.queryProxyGroupNames(excludeNotSelectable) }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup =
        guardSync("queryProxyGroup") { delegate.queryProxyGroup(name, proxySort) }

    override fun queryProviders(): ProviderList =
        guardSync("queryProviders") { delegate.queryProviders() }

    override fun patchSelector(group: String, name: String): Boolean =
        guardSync("patchSelector") { delegate.patchSelector(group, name) }

    override fun rememberSelection(group: String, name: String) =
        guardSync("rememberSelection") { delegate.rememberSelection(group, name) }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride =
        guardSync("queryOverride") { delegate.queryOverride(slot) }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) =
        guardSync("patchOverride") { delegate.patchOverride(slot, configuration) }

    override fun clearOverride(slot: Clash.OverrideSlot) =
        guardSync("clearOverride") { delegate.clearOverride(slot) }

    override fun setLogObserver(observer: ILogObserver?) =
        guardSync("setLogObserver") { delegate.setLogObserver(observer) }

    override suspend fun querySelections(): Map<String, String> =
        guard { delegate.querySelections() }

    override suspend fun healthCheck(group: String) =
        guard { delegate.healthCheck(group) }

    override suspend fun testProfileDelays(uuid: UUID): String =
        guard { delegate.testProfileDelays(uuid) }

    override suspend fun updateProvider(type: Provider.Type, name: String) =
        guard { delegate.updateProvider(type, name) }
}

class GuardedProfileManager(private val delegate: IProfileManager) : IProfileManager by delegate {
    override suspend fun create(
        type: Profile.Type,
        name: String,
        source: String,
        ageSecretKey: String?,
        secure: Boolean,
    ): UUID = guard { delegate.create(type, name, source, ageSecretKey, secure) }

    override suspend fun clone(uuid: UUID): UUID =
        guard { delegate.clone(uuid) }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) =
        guard { delegate.commit(uuid, callback) }

    override suspend fun release(uuid: UUID) =
        guard { delegate.release(uuid) }

    override suspend fun delete(uuid: UUID) =
        guard { delegate.delete(uuid) }

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?) =
        guard { delegate.patch(uuid, name, source, interval, ageSecretKey) }

    override suspend fun update(uuid: UUID) =
        guard { delegate.update(uuid) }

    override suspend fun queryByUUID(uuid: UUID): Profile? =
        guard { delegate.queryByUUID(uuid) }

    override suspend fun queryAll(): List<Profile> =
        guard { delegate.queryAll() }

    override suspend fun queryActive(): Profile? =
        guard { delegate.queryActive() }

    override suspend fun setActive(profile: Profile) =
        guard { delegate.setActive(profile) }
}
