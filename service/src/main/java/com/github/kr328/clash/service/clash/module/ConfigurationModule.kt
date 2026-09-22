package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.os.SystemClock
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.GeoAssets
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.ProfileProcessor
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.ModeChoiceDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.data.Selections
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ActiveProfileAction
import com.github.kr328.clash.service.util.activeProfileGone
import com.github.kr328.clash.service.util.activeProfileRollback
import com.github.kr328.clash.service.util.displayProfileName
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendClashStarting
import com.github.kr328.clash.service.util.sendProfileLoadFailed
import com.github.kr328.clash.service.util.sendProfileLoaded
import com.github.kr328.clash.service.util.sessionOverrideFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.Event>(service) {
    sealed class Event {
        data class Loaded(val uuid: UUID) : Event()
        data class LoadFailed(val message: String) : Event()
    }

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    private fun forgetMissingProfile(missing: UUID): Nothing {
        if (activeProfileGone(store.activeProfile, missing) == ActiveProfileAction.Clear) {
            store.activeProfile = null
        }

        throw NullPointerException("No profile selected")
    }

    private fun stage(stage: String) {
        StatusProvider.startupStage = stage

        service.sendClashStarting(stage)
    }

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null
        var ready = false

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            var current: UUID? = null

            try {
                current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                val active = ImportedDao().queryByUUID(current)
                    ?: forgetMissingProfile(current)

                val first = loaded == null

                if (first) stage(Intents.STAGE_PREPARING)

                GeoAssets.awaitReady(service)

                ProfileProcessor.repair(service)

                if (first) stage(Intents.STAGE_LOADING)

                Clash.patchOverride(
                    Clash.OverrideSlot.Session,
                    sessionOverrideFor(ModeChoiceDao().queryChoice(active.uuid)),
                )

                // Окно мерит только загрузку ядра: один Clash.load(...).await().
                // Ожидание гео-баз, repair и выбор узлов идут отдельными стадиями
                // (STAGE_PREPARING / STAGE_SELECTING) и сюда не входят, поэтому
                // цифру из журнала нельзя противопоставлять жалобе «подключение
                // применяется N секунд».
                val applyStartedAt = SystemClock.elapsedRealtime()

                ServiceLog.mark("config: core load window start")

                var applyOutcome = "failed"

                try {
                    Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                    applyOutcome = "ok"
                } catch (e: CancellationException) {
                    applyOutcome = "cancelled"

                    throw e
                } finally {
                    ServiceLog.mark(
                        "config: core load window end, $applyOutcome, in " +
                            "${SystemClock.elapsedRealtime() - applyStartedAt} ms",
                    )
                }

                loaded = current

                if (first) stage(Intents.STAGE_SELECTING)

                withContext(Selections.queue) {
                    val remove = SelectionDao().querySelections(active.uuid)
                        .filter {
                            Clash.patchSelector(it.proxy, it.selected) == Clash.PatchResult.NoSelector
                        }
                        .map { it.proxy }

                    SelectionDao().removeSelections(active.uuid, remove)
                }

                StatusProvider.currentProfile =
                    service.displayProfileName(active.uuid, active.name)

                StatusProvider.currentProfileUuid = active.uuid.toString()

                service.sendProfileLoaded(current)

                enqueueEvent(Event.Loaded(current))

                ready = true

                Log.d("Profile ${active.name} loaded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unknown"
                val retained = loaded
                val failed = current?.let { ImportedDao().queryByUUID(it) }

                if (!ready || retained == null || current == null || failed == null) {
                    return enqueueEvent(Event.LoadFailed(message))
                }

                val rollback = activeProfileRollback(
                    store.activeProfile,
                    current,
                    retained,
                    ImportedDao().exists(retained),
                )

                if (rollback == ActiveProfileAction.Restore) {
                    store.activeProfile = retained
                }

                val retainedName = StatusProvider.currentProfile ?: retained.toString()

                Log.w("Profile ${failed.name} failed to load, keeping $retainedName: $message")

                val failedName = service.displayProfileName(failed.uuid, failed.name)

                service.sendProfileLoadFailed(
                    current,
                    if (current == retained)
                        service.getString(R.string.clod_profile_reload_failed, failedName, message)
                    else
                        service.getString(R.string.clod_profile_load_failed, failedName, message, retainedName)
                )
            }
        }
    }
}
