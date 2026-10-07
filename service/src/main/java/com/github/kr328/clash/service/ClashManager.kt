package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.clash.module.ConfigurationModule
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.ModeChoice
import com.github.kr328.clash.service.data.ModeChoiceDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.data.Selections
import com.github.kr328.clash.service.freeze.FreezeChecks
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.modeChoiceChanged
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.service.util.sessionOverrideFor
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.UUID

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private val selections = Selections.queue
    private var logReceiver: ReceiveChannel<LogMessage>? = null
    private var markReceiver: Job? = null

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): ProxyGroupNames {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String): ProxyGroup {
        return Clash.queryGroup(name)
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    // Выбор принадлежит подписке, на чьём списке его сделали. Держит её ядро —
    // выбор уходит в ядро и записывается; нет (грузится другая, туннеля нет) —
    // только записывается и применится при её загрузке. Держит ли, проверяет само
    // ядро под замком смены конфига; запись идёт под замком выборов, что и
    // возврат выбора после загрузки, — выбор не теряется и не попадает в чужую
    // подписку.
    override suspend fun select(profile: UUID, group: String, name: String): Boolean = withContext(selections) {
        Selections.lock.withLock {
            val result = Clash.patchSelector(profile.toString(), group, name)

            try {
                when (result) {
                    // Пустое имя — закрепление снято, группа выбирает сама
                    Clash.PatchResult.Done, Clash.PatchResult.NotLoaded ->
                        if (name.isEmpty()) {
                            SelectionDao().removeSelected(profile, group)
                        } else {
                            SelectionDao().setSelected(Selection(profile, group, name))
                        }
                    Clash.PatchResult.NoSelector ->
                        SelectionDao().removeSelected(profile, group)
                    // Скрытый узел (только для мобильной сети) не выбран и не запомнен
                    Clash.PatchResult.Failed, Clash.PatchResult.Hidden -> Unit
                }
            } catch (e: Exception) {
                Log.w("Remember selection $name for $group: $e", e)
            }

            result == Clash.PatchResult.Done || result == Clash.PatchResult.NotLoaded
        }
    }

    override suspend fun querySelections(): Map<String, String> = withContext(selections) {
        val current = store.activeProfile ?: return@withContext emptyMap()

        SelectionDao().querySelections(current).associate { it.proxy to it.selected }
    }

    override fun queryFreezeMarks(uuid: UUID): Map<String, String> = FreezeChecks.marks(uuid)

    override suspend fun testProfileDelays(uuid: UUID): String = withContext(Dispatchers.IO) {
        Clash.testProfileDelays(context.importedDir.resolve(uuid.toString()))
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    // Режим — свойство подписки (её файл и её выбор), а не «активной»: экран
    // спрашивает о той, что показывает, и чужой ответ получить не может.
    override suspend fun queryProfileMode(uuid: UUID): ProfileMode = withContext(Dispatchers.IO) {
        ProfileProcessor.queryMode(
            context,
            uuid,
            sessionOverrideFor(ModeChoiceDao().queryChoice(uuid)),
        )
    }

    // Выбор для неактивной подписки — только запись: перезагрузку ядра заказывает
    // лишь выбор активной, а удалённой подписке записывать нечего.
    override suspend fun setProfileMode(uuid: UUID, mode: TunnelState.Mode) = withContext(Dispatchers.IO) {
        if (ImportedDao().queryByUUID(uuid) == null) return@withContext

        if (!modeChoiceChanged(ModeChoiceDao().queryChoice(uuid), mode)) return@withContext

        ModeChoiceDao().setChoice(ModeChoice(uuid, mode))

        if (!switchModeLive(uuid, mode) && uuid == store.activeProfile) {
            context.sendOverrideChanged()
        }
    }

    private suspend fun switchModeLive(current: UUID, mode: TunnelState.Mode): Boolean {
        if (StatusProvider.currentProfileUuid != current.toString()) return false

        if (!ConfigurationModule.coreLoad.tryLock()) return false

        try {
            val session = sessionOverrideFor(mode)

            Clash.patchOverride(Clash.OverrideSlot.Session, session)

            val switched = ProfileProcessor.switchMode(context, current, session)

            if (switched) {
                ServiceLog.mark("config: mode switched to $mode without reload")
            }

            return switched
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Живое переключение не удалось — выбор уже записан, его применит
            // полная перезагрузка профиля.
            Log.w("Switch mode live: $e", e)

            return false
        } finally {
            ConfigurationModule.coreLoad.unlock()
        }
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)

        context.sendOverrideChanged()
    }

    override fun reloadGeoData() {
        Clash.reloadGeoData()
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun healthCheckGroups(groups: List<String>, exclude: List<String>, force: Boolean) {
        return Clash.healthCheckGroups(groups, exclude, force).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            markReceiver?.cancel()

            markReceiver = null

            if (observer != null) {
                markReceiver = launch {
                    try {
                        while (isActive) {
                            observer.newItem(ServiceLog.events.receive())
                        }
                    } catch (e: CancellationException) {
                    } catch (e: Exception) {
                        Log.w("UI crashed", e)
                    }
                }

                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}
