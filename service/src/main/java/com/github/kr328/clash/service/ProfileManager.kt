package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.util.*
import java.util.concurrent.TimeUnit

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)

    init {
        launch {
            try {
                Database.database

                ProfileProcessor.repair(context)
                ProfileProcessor.releaseStale(context, STALE_PENDING_MS)

                ProfileUpdates.scheduleAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Reschedule profile updates: $e", e)
            }
        }
    }

    override suspend fun create(
        type: Profile.Type,
        name: String,
        source: String,
        secure: Boolean,
    ): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = 0,
            upload = 0,
            total = 0,
            download = 0,
            expire = 0,
            secure = secure,
        )

        PendingDao().insert(pending)

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun patch(uuid: UUID, name: String, nameManual: Boolean, source: String, interval: Long, intervalManual: Boolean) {
        val pending = PendingDao().queryByUUID(uuid)

        val opened = pending == null &&
            ProfileProcessor.openDraft(context, uuid) { imported ->
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    upload = 0,
                    total = 0,
                    download = 0,
                    expire = 0,
                    secure = imported.secure,
                    intervalManual = intervalManual,
                    nameManual = nameManual,
                )
            }

        if (!opened) {
            // Черновик мог завестись параллельно, пока openDraft ждал замка — тогда правим его.
            val current = pending ?: PendingDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            val newPending = current.copy(
                name = name,
                source = source,
                interval = interval,
                upload = 0,
                total = 0,
                download = 0,
                expire = 0,
                touchedAt = System.currentTimeMillis(),
                intervalManual = intervalManual,
                nameManual = nameManual,
            )

            PendingDao().update(newPending)

            if (!context.pendingDir.resolve(uuid.toString()).setLastModified(System.currentTimeMillis())) {
                Log.w("Draft $uuid: cannot refresh its directory time")
            }
        }
    }

    override suspend fun update(uuid: UUID) {
        scheduleUpdate(uuid, true)
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)

        scheduleUpdate(uuid, false)
    }

    override suspend fun release(uuid: UUID) {
        ProfileProcessor.release(context, uuid)
    }

    override suspend fun delete(uuid: UUID) {
        ImportedDao().queryByUUID(uuid)?.also {
            ProfileUpdates.cancel(context, it)
        }

        ProfileProcessor.delete(context, uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? {
        return resolveProfile(uuid)
    }

    override suspend fun queryAll(): List<Profile> {
        val uuids = (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs()).distinct()

        return uuids.mapNotNull { resolveProfile(it) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active)) {
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)

        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = imported?.upload ?: pending?.upload ?: return null
        val download = imported?.download ?: pending?.download ?: return null
        val total = imported?.total ?: pending?.total ?: return null
        val expire = imported?.expire ?: pending?.expire ?: return null

        return Profile(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            active = active != null && imported?.uuid == active,
            interval = interval,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            updatedAt = resolveUpdatedAt(uuid),
            imported = imported != null,
            pending = pending != null,
            secure = if (pending != null) pending.secure else imported?.secure ?: false,
            intervalManual = pending?.intervalManual ?: imported?.intervalManual ?: false,
            quota = imported?.quota ?: false,
            nameManual = pending?.nameManual ?: imported?.nameManual ?: false,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        return context.importedDir.resolve(uuid.toString()).directoryLastModified
            ?: context.pendingDir.resolve(uuid.toString()).directoryLastModified
            ?: -1
    }

    private suspend fun scheduleUpdate(uuid: UUID, startImmediately: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        if (startImmediately) {
            ProfileUpdates.updateNow(context, imported)
        } else {
            ProfileUpdates.schedule(context, imported)
        }
    }

    private companion object {
        val STALE_PENDING_MS = TimeUnit.HOURS.toMillis(6)
    }
}
