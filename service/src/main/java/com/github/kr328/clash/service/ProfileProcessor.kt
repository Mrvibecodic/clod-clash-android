package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.subscription.notifySubscriptionMoved
import com.github.kr328.clash.service.util.DraftFreshness
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.UpdateFailures
import com.github.kr328.clash.service.util.UpdateSchedule
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.ProfileSwap
import com.github.kr328.clash.service.util.ActiveProfileAction
import com.github.kr328.clash.service.util.activeProfileGone
import com.github.kr328.clash.service.util.activeProfileSelect
import com.github.kr328.clash.service.util.applyDeviceInfo
import com.github.kr328.clash.service.util.copyProfileTo
import com.github.kr328.clash.service.util.ProfileFields
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.readPanelInfo
import com.github.kr328.clash.service.util.seedSystemDns
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.*

object ProfileProcessor {
    private fun Pending.sameDraft(other: Pending): Boolean =
        name == other.name &&
            type == other.type &&
            source == other.source &&
            interval == other.interval &&
            secure == other.secure &&
            intervalManual == other.intervalManual &&
            nameManual == other.nameManual

    class Fetched(val info: FetchStatus?, val failedProviders: List<String>)

    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun queryMode(context: Context, uuid: UUID, session: ConfigurationOverride): ProfileMode =
        profileLock.withLock {
            Clash.queryModeOf(context.importedDir.resolve(uuid.toString()), session)
        }

    suspend fun switchMode(context: Context, uuid: UUID, session: ConfigurationOverride): Boolean =
        profileLock.withLock {
            Clash.switchMode(context.importedDir.resolve(uuid.toString()), session)
        }

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending =
                        PendingDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    repairLocked(context)

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyProfileTo(context.processingDir, overwrite = true)

                    pending
                }

                val force = snapshot.type != Profile.Type.File
                val subscriptionInfo =
                    fetchProfile(context, context.processingDir, snapshot.source, force, false, snapshot.secure, callback).info

                profileLock.withLock {
                    val current = PendingDao().queryByUUID(snapshot.uuid)

                    if (current == null || !current.sameDraft(snapshot)) {
                        Log.w("Draft $uuid changed while its subscription was loading, result dropped")

                        throw IllegalStateException(UpdateFailures.DRAFT_CHANGED)
                    }

                    ProfileSwap.replace(
                        context.importedDir.resolve(snapshot.uuid.toString()),
                        context.processingDir,
                        warn = { Log.w(it) },
                    )

                    val old = ImportedDao().queryByUUID(snapshot.uuid)
                    val manual = snapshot.intervalManual
                    val updateInterval = UpdateSchedule.effectiveInterval(manual, subscriptionInfo?.subUpdateInterval, snapshot.interval)
                    val new = Imported(
                        snapshot.uuid,
                        snapshot.name,
                        snapshot.type,
                        snapshot.source,
                        updateInterval,
                        subscriptionInfo?.subUpload ?: 0,
                        subscriptionInfo?.subDownload ?: 0,
                        subscriptionInfo?.subTotal ?: 0,
                        subscriptionInfo?.subExpire ?: 0,
                        old?.createdAt ?: System.currentTimeMillis(),
                        secure = snapshot.secure,
                        intervalManual = manual,
                        quota = subscriptionInfo?.subUpload != null,
                        nameManual = snapshot.nameManual,
                    )
                    if (old != null) {
                        ImportedDao().update(new)
                    } else {
                        ImportedDao().insert(new)
                    }

                    Clash.markProfileUpdated(context.importedDir.resolve(snapshot.uuid.toString()), updateInterval)

                    PendingDao().remove(snapshot.uuid)

                    context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()

                    context.sendProfileChanged(snapshot.uuid)
                }

                followMove(context, snapshot.uuid, snapshot.source, snapshot.secure, callback)
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID): List<String> {
        return withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    repairLocked(context)

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyProfileTo(context.processingDir, overwrite = true)

                    imported
                }

                val fetched = fetchProfile(context, context.processingDir, snapshot.source, true, false, snapshot.secure, null)
                val subscriptionInfo = fetched.info

                profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(snapshot.uuid)
                    if (imported != null) {
                        ProfileSwap.replace(
                            context.importedDir.resolve(snapshot.uuid.toString()),
                            context.processingDir,
                            warn = { Log.w(it) },
                        )

                        val upload = subscriptionInfo?.subUpload
                        val refreshed = if (upload != null) {
                            imported.copy(
                                upload = upload,
                                download = subscriptionInfo.subDownload ?: 0,
                                total = subscriptionInfo.subTotal ?: 0,
                                expire = subscriptionInfo.subExpire ?: 0,
                                quota = true,
                            )
                        } else {
                            imported
                        }
                        val stored = refreshed.copy(
                            interval = UpdateSchedule.effectiveInterval(
                                imported.intervalManual,
                                subscriptionInfo?.subUpdateInterval,
                                imported.interval,
                            ),
                        )
                        if (stored != imported) {
                            ImportedDao().update(stored)
                        }

                        Clash.markProfileUpdated(context.importedDir.resolve(snapshot.uuid.toString()), stored.interval)

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }

                val moved = followMove(context, snapshot.uuid, snapshot.source, snapshot.secure)

                (fetched.failedProviders + moved).distinct()
            }
        }
    }

    private suspend fun followMove(
        context: Context,
        uuid: UUID,
        current: String,
        secure: Boolean,
        callback: IFetchObserver? = null,
    ): List<String> = try {
        moveToSpareAddress(context, uuid, current, secure, callback)
    } catch (e: Exception) {
        Log.w("Move $uuid to the spare address: $e", e)

        emptyList()
    }

    // Провайдер велел перевести подписку на запасной адрес (clod-move-sub). Адрес меняется
    // навсегда, только если запасной сам отдал годную подписку; проба идёт в копии каталога
    // подписки, живой до удачи не трогается. Выбор узлов, режим и имя не сбрасываются.
    // Петли нет: запасной домен, совпавший с хостом основного адреса, пустой (panel.go).
    private suspend fun moveToSpareAddress(
        context: Context,
        uuid: UUID,
        current: String,
        secure: Boolean,
        callback: IFetchObserver?,
    ): List<String> {
        val target = context.readPanelInfo(uuid)?.moveUrl.orEmpty()
        if (target.isBlank() || target == current) {
            return emptyList()
        }

        val profileDir = context.importedDir.resolve(uuid.toString())
        val probe = context.processingDir

        profileLock.withLock {
            probe.deleteRecursively()

            profileDir.copyProfileTo(probe, overwrite = true)
        }

        val fetched = try {
            fetchProfile(context, probe, target, true, true, secure, callback)
        } catch (e: Exception) {
            Log.w("Spare address of $uuid did not answer, keeping the current one: $e", e)

            probe.deleteRecursively()

            return emptyList()
        }

        val info = fetched.info

        val moved = profileLock.withLock {
            val imported = ImportedDao().queryByUUID(uuid) ?: return@withLock false

            // Адрес для перевода построен от того основного, с которого шло обновление; сменили
            // его за это время — перевод к подписке уже не относится.
            if (imported.source != current) return@withLock false

            ProfileSwap.replace(profileDir, probe, warn = { Log.w(it) })

            ImportedDao().update(
                imported.copy(
                    source = target,
                    upload = info?.subUpload ?: imported.upload,
                    download = info?.subDownload ?: imported.download,
                    total = info?.subTotal ?: imported.total,
                    expire = info?.subExpire ?: imported.expire,
                    quota = info?.subUpload != null || imported.quota,
                ),
            )

            Clash.markProfileUpdated(profileDir, imported.interval)

            true
        }

        probe.deleteRecursively()

        if (!moved) return emptyList()

        Log.i("Subscription $uuid moved to the spare address by the provider")

        context.sendProfileChanged(uuid)
        context.notifySubscriptionMoved(uuid)

        return fetched.failedProviders
    }

    suspend fun repair(context: Context) {
        withContext(NonCancellable) {
            profileLock.withLock {
                repairLocked(context)
            }
        }
    }

    private fun repairLocked(context: Context) {
        val repairs = try {
            ProfileSwap.repair(context.importedDir)
        } catch (e: Exception) {
            Log.e("Repair profile directories: $e", e)

            return
        }

        for (repair in repairs) {
            when (repair) {
                is ProfileSwap.Repair.Restored -> Log.w("Profile ${repair.name} restored from an interrupted update")
                is ProfileSwap.Repair.Dropped -> Log.i("Profile ${repair.name}: leftover of a finished update removed")
            }
        }
    }

    private suspend fun fetchProfile(
        context: Context,
        dir: File,
        source: String,
        force: Boolean,
        probe: Boolean,
        secure: Boolean,
        callback: IFetchObserver?,
    ): Fetched {
        val reports = FetchReports(callback)

        context.applyDeviceInfo()

        GeoAssets.awaitReady(context)

        context.seedSystemDns()

        Clash.fetchAndValid(dir, source, force, probe, secure) {
            if (reports.record(it)) {
                val observer = reports.observer()

                if (observer != null) {
                    try {
                        observer.updateStatus(it)
                    } catch (e: Exception) {
                        reports.drop()

                        Log.w("Report fetch status: $e", e)
                    }
                }
            }
        }.await()

        return Fetched(reports.info(), reports.failed())
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                ProfileSwap.staleOf(imported).deleteRecursively()
                imported.deleteRecursively()

                val store = ServiceStore(context)

                if (activeProfileGone(store.activeProfile, uuid) == ActiveProfileAction.Clear) {
                    store.activeProfile = null
                }

                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun releaseStale(context: Context, maxAge: Long) {
        withContext(NonCancellable) {
            profileLock.withLock {
                val now = System.currentTimeMillis()

                for (uuid in PendingDao().queryAllUUIDs()) {
                    val pending = PendingDao().queryByUUID(uuid) ?: continue
                    val dir = context.pendingDir.resolve(uuid.toString())

                    val stale = DraftFreshness.stale(
                        createdAt = pending.createdAt,
                        touchedAt = pending.touchedAt,
                        directoryModifiedAt = dir.directoryLastModified ?: 0L,
                        now = now,
                        maxAge = maxAge,
                    )

                    if (!stale) continue

                    PendingDao().remove(uuid)
                    dir.deleteRecursively()

                    Log.w("Stale draft $uuid released")

                    if (!ImportedDao().exists(uuid)) {
                        context.sendProfileChanged(uuid)
                    }
                }

                // Каталог без строки черновика остаётся, если процесс умер между копированием и
                // вставкой строки. Список каталогов снимается раньше списка строк: create вставляет
                // строку до того, как заводит каталог, поэтому живой черновик сюда не попадёт.
                val dirs = context.pendingDir.listFiles().orEmpty()
                val drafts = PendingDao().queryAllUUIDs().mapTo(HashSet()) { it.toString() }

                for (dir in dirs) {
                    if (dir.name in drafts) continue

                    dir.deleteRecursively()

                    Log.w("Orphan draft directory ${dir.name} removed")
                }
            }
        }
    }

    // Черновик из рабочей подписки заводится под тем же замком, что и подмена каталога:
    // иначе копия может попасть между двумя переименованиями ProfileSwap.
    suspend fun openDraft(context: Context, uuid: UUID, draft: (Imported) -> Pending): Boolean =
        withContext(NonCancellable) {
            profileLock.withLock {
                if (PendingDao().exists(uuid)) return@withLock false

                repairLocked(context)

                val imported = ImportedDao().queryByUUID(uuid)
                    ?: throw FileNotFoundException("profile $uuid not found")

                val source = context.importedDir.resolve(uuid.toString())
                val target = context.pendingDir.resolve(uuid.toString())

                if (!source.exists()) throw FileNotFoundException("profile $uuid not found")

                target.deleteRecursively()
                source.copyProfileTo(target)

                PendingDao().insert(draft(imported))

                true
            }
        }

    suspend fun release(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                val store = ServiceStore(context)

                if (activeProfileSelect(store.activeProfile, uuid, ImportedDao().exists(uuid)) == ActiveProfileAction.Set) {
                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        val violation = ProfileFields.violation(
            name,
            source,
            scheme,
            interval,
            type != Profile.Type.File,
        ) ?: return

        throw IllegalArgumentException(
            when (violation) {
                ProfileFields.Violation.EmptyName -> "Empty name"

                ProfileFields.Violation.NameTooLong ->
                    "Name longer than ${ProfileFields.NAME_MAX} characters"

                ProfileFields.Violation.EmptySource -> "Invalid url"

                ProfileFields.Violation.SourceTooLong ->
                    "Url longer than ${ProfileFields.SOURCE_MAX} characters"

                ProfileFields.Violation.UnsupportedScheme -> "Unsupported url $source"

                ProfileFields.Violation.ShortInterval -> "Invalid interval"
            }
        )
    }

}
