package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.migrationDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.ProfileSwap
import com.github.kr328.clash.service.util.ActiveProfileAction
import com.github.kr328.clash.service.util.activeProfileGone
import com.github.kr328.clash.service.util.applyDeviceInfo
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.readPanelInfo
import com.github.kr328.clash.service.util.seedSystemDns
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private const val MAX_MIGRATION_HOPS = 3

    private const val MAX_MIGRATION_HISTORY = 10

    private val MIGRATION_HOPS_WINDOW_MS = TimeUnit.DAYS.toMillis(1)

    private val MIGRATION_HISTORY_WINDOW_MS = TimeUnit.DAYS.toMillis(7)

    private const val MIGRATION_FILE = "migration.json"

    private const val PROVIDERS_DIR = "providers"

    private fun Pending.sameDraft(other: Pending): Boolean =
        name == other.name &&
            type == other.type &&
            source == other.source &&
            interval == other.interval &&
            ageSecretKey == other.ageSecretKey &&
            secure == other.secure

    class Fetched(val info: FetchStatus?, val failedProviders: List<String>)

    private val migrationJson = Json { ignoreUnknownKeys = true }

    private val profileLock = Mutex()
    private val processLock = Mutex()

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

                    context.migrationDir.deleteRecursively()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })
                Clash.setSecureChannel(snapshot.secure)

                val force = snapshot.type != Profile.Type.File
                val subscriptionInfo =
                    fetchProfile(context, context.processingDir, snapshot.source, force, false, callback).info

                profileLock.withLock {
                    val current = PendingDao().queryByUUID(snapshot.uuid)

                    if (current == null || !current.sameDraft(snapshot)) {
                        Log.w("Draft $uuid changed while its subscription was loading, result dropped")

                        throw IllegalStateException(context.getString(R.string.clod_draft_changed))
                    }

                    ProfileSwap.replace(
                        context.importedDir.resolve(snapshot.uuid.toString()),
                        context.processingDir,
                        warn = { Log.w(it) },
                    )

                    val old = ImportedDao().queryByUUID(snapshot.uuid)
                    val updateInterval = subscriptionInfo?.subUpdateInterval
                        ?.takeIf { old == null && snapshot.interval == 0L }
                        ?: snapshot.interval
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
                        ageSecretKey = snapshot.ageSecretKey,
                        secure = snapshot.secure
                    )
                    if (old != null) {
                        ImportedDao().update(new)
                    } else {
                        ImportedDao().insert(new)
                    }

                    PendingDao().remove(snapshot.uuid)

                    context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()

                    context.sendProfileChanged(snapshot.uuid)
                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?): List<String> {
        return withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported =
                        ImportedDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    repairLocked(context)

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.migrationDir.deleteRecursively()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })
                Clash.setSecureChannel(snapshot.secure)

                val fetched = fetchProfile(context, context.processingDir, snapshot.source, true, false, callback)
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
                        if (upload != null) {
                            ImportedDao().update(
                                imported.copy(
                                    upload = upload,
                                    download = subscriptionInfo.subDownload ?: 0,
                                    total = subscriptionInfo.subTotal ?: 0,
                                    expire = subscriptionInfo.subExpire ?: 0,
                                )
                            )
                        }

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }

                val migrated = followMigration(context, snapshot.uuid, snapshot.source, callback)

                (fetched.failedProviders + migrated).distinct()
            }
        }
    }

    private suspend fun followMigration(
        context: Context,
        uuid: UUID,
        current: String,
        callback: IFetchObserver?,
    ): List<String> {
        val profileDir = context.importedDir.resolve(uuid.toString())
        val stateFile = profileDir.resolve(MIGRATION_FILE)

        val candidate = context.readPanelInfo(uuid)?.migrateUrl.orEmpty()
        if (candidate.isBlank() || candidate == current) {
            return emptyList()
        }

        val now = System.currentTimeMillis()

        val state = readMigration(stateFile).let {
            it.copy(
                hops = if (it.hops > 0 && now - it.lastAt > MIGRATION_HOPS_WINDOW_MS) 0 else it.hops,
                history = it.history.filter { visit -> now - visit.at < MIGRATION_HISTORY_WINDOW_MS },
            )
        }

        if (state.history.any { it.url == candidate }) {
            Log.w("Migration of $uuid ignored: the address was already left behind, looks like a loop")

            return emptyList()
        }

        if (state.hops >= MAX_MIGRATION_HOPS) {
            Log.w("Migration of $uuid ignored: ${state.hops} hops already followed")

            return emptyList()
        }

        val probe = context.migrationDir

        val fetched = try {
            probe.deleteRecursively()
            probe.mkdirs()

            profileDir.resolve(PROVIDERS_DIR).takeIf { it.isDirectory }
                ?.copyRecursively(probe.resolve(PROVIDERS_DIR), overwrite = true)

            fetchProfile(context, probe, candidate, true, true, callback)
        } catch (e: Exception) {
            Log.w("Migration of $uuid to a new address failed, keeping the current one: $e", e)

            probe.deleteRecursively()

            return emptyList()
        }

        val info = fetched.info

        profileLock.withLock {
            val imported = ImportedDao().queryByUUID(uuid) ?: return@withLock

            ProfileSwap.replace(profileDir, probe, warn = { Log.w(it) })

            ImportedDao().update(
                imported.copy(
                    source = candidate,
                    upload = info?.subUpload ?: imported.upload,
                    download = info?.subDownload ?: imported.download,
                    total = info?.subTotal ?: imported.total,
                    expire = info?.subExpire ?: imported.expire,
                ),
            )

            writeMigration(
                stateFile,
                MigrationState(
                    hops = state.hops + 1,
                    history = (state.history + MigrationVisit(current, now)).takeLast(MAX_MIGRATION_HISTORY),
                    lastAt = now,
                ),
            )
        }

        probe.deleteRecursively()

        Log.i("Subscription $uuid migrated to a new address by the provider")

        context.sendProfileChanged(uuid)

        return fetched.failedProviders
    }

    @Serializable
    private data class MigrationState(
        val hops: Int = 0,
        val history: List<MigrationVisit> = emptyList(),
        val lastAt: Long = 0,
    )

    @Serializable
    private data class MigrationVisit(
        val url: String,
        val at: Long,
    )

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

    private fun readMigration(file: File): MigrationState {
        if (!file.isFile) return MigrationState()

        return try {
            migrationJson.decodeFromString(MigrationState.serializer(), file.readText())
        } catch (e: Exception) {
            Log.w("Read $MIGRATION_FILE: $e", e)

            MigrationState()
        }
    }

    private fun writeMigration(file: File, state: MigrationState) {
        try {
            file.writeText(migrationJson.encodeToString(MigrationState.serializer(), state))
        } catch (e: Exception) {
            Log.w("Write $MIGRATION_FILE: $e", e)
        }
    }

    private suspend fun fetchProfile(
        context: Context,
        dir: File,
        source: String,
        force: Boolean,
        probe: Boolean,
        callback: IFetchObserver?,
    ): Fetched {
        var subscriptionInfo: FetchStatus? = null
        val failedProviders = ArrayList<String>()
        var cb = callback

        context.applyDeviceInfo()

        GeoAssets.awaitReady(context)

        context.seedSystemDns()

        Clash.fetchAndValid(dir, source, force, probe) {
            when (it.action) {
                FetchStatus.Action.SubscriptionInfo -> {
                    subscriptionInfo = it
                    return@fetchAndValid
                }
                FetchStatus.Action.ProviderFailed -> synchronized(failedProviders) {
                    it.args.firstOrNull()?.let(failedProviders::add)
                }
                else -> Unit
            }

            try {
                cb?.updateStatus(it)
            } catch (e: Exception) {
                cb = null

                Log.w("Report fetch status: $e", e)
            }
        }.await()

        return Fetched(subscriptionInfo, synchronized(failedProviders) { failedProviders.toList() })
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()
                ProfileSwap.staleOf(imported).deleteRecursively()

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
                    val touched = maxOf(pending.createdAt, dir.directoryLastModified ?: 0L)

                    if (now - touched < maxAge) continue

                    PendingDao().remove(uuid)
                    dir.deleteRecursively()

                    Log.w("Stale draft $uuid released")

                    if (!ImportedDao().exists(uuid)) {
                        context.sendProfileChanged(uuid)
                    }
                }
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "content" -> throw IllegalArgumentException(
                "Unsupported url $source"
            )

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

}
