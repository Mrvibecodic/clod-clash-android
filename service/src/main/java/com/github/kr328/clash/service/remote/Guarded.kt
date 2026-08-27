package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.model.Provider
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

class GuardedClashManager(private val delegate: IClashManager) : IClashManager by delegate {
    override suspend fun querySelection(group: String): String? =
        guard { delegate.querySelection(group) }

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
