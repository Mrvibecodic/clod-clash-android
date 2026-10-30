package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.freeze.FreezeChecks
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.fetchedAt
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.pendingDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        interval: Long,
        intervalManual: Boolean,
        nameManual: Boolean,
    ): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = interval,
            secure = secure,
            intervalManual = intervalManual,
            nameManual = nameManual,
        )

        PendingDao().insert(pending)

        try {
            context.pendingDir.resolve(uuid.toString()).apply {
                deleteRecursively()
                mkdirs()

                @Suppress("BlockingMethodInNonBlockingContext")
                resolve("config.yaml").createNewFile()
                resolve("providers").mkdir()
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                PendingDao().remove(uuid)
            }

            throw e
        }

        return uuid
    }

    override suspend fun patch(
        uuid: UUID,
        name: String,
        nameManual: Boolean,
        source: String,
        interval: Long,
        intervalManual: Boolean,
        secure: Boolean,
    ) {
        val pending = PendingDao().queryByUUID(uuid)

        val opened = pending == null &&
            ProfileProcessor.openDraft(context, uuid) { imported ->
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    secure = secure,
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
                touchedAt = System.currentTimeMillis(),
                intervalManual = intervalManual,
                nameManual = nameManual,
                secure = secure,
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

    override suspend fun commit(uuid: UUID, activate: Boolean, callback: IFetchObserver?): UUID =
        withContext(NonCancellable) {
            val saved = ProfileProcessor.apply(context, uuid, callback, activate)

            if (saved == uuid) {
                // Сохранение (у подписки по ссылке — с новой загрузкой) без туннеля
                // сверяет пометки 16–20: панель могла включить или выключить проверку
                FreezeChecks.profileUpdated(uuid)

                scheduleUpdate(uuid, false)
            }

            saved
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
        val imported = ImportedDao().queryAll().associateBy { it.uuid }
        val pending = PendingDao().queryAll().associateBy { it.uuid }

        return (imported.keys + pending.keys).mapNotNull { resolveProfile(it, imported[it], pending[it], draft = false) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return resolveProfile(active, ImportedDao().queryByUUID(active), PendingDao().queryByUUID(active), draft = false)
            ?.takeIf { it.imported }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? =
        resolveProfile(uuid, ImportedDao().queryByUUID(uuid), PendingDao().queryByUUID(uuid), draft = true)

    // draft — поля черновика поверх рабочей подписки (экран правки). Списки и
    // активная подписка — то, что работает: несохранённая правка в них не видна
    private fun resolveProfile(uuid: UUID, imported: Imported?, pending: Pending?, draft: Boolean): Profile? {
        val active = store.activeProfile
        val edit = pending?.takeIf { draft || imported == null }
        val name = edit?.name ?: imported?.name ?: return null
        val type = edit?.type ?: imported?.type ?: return null
        val source = edit?.source ?: imported?.source ?: return null
        val interval = edit?.interval ?: imported?.interval ?: return null
        val upload = imported?.upload ?: edit?.upload ?: return null
        val download = imported?.download ?: edit?.download ?: return null
        val total = imported?.total ?: edit?.total ?: return null
        val expire = imported?.expire ?: edit?.expire ?: return null
        val fetchedAt = if (imported != null) context.fetchedAt(uuid) else 0

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
            imported = imported != null,
            pending = pending != null,
            secure = if (edit != null) edit.secure else imported?.secure ?: false,
            intervalManual = edit?.intervalManual ?: imported?.intervalManual ?: false,
            quota = imported?.quota ?: false,
            nameManual = edit?.nameManual ?: imported?.nameManual ?: false,
            fetchedAt = fetchedAt,
            importedSecure = imported?.secure ?: false,
        )
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
