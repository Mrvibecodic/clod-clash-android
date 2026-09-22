package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.ModeChoice
import com.github.kr328.clash.service.data.ModeChoiceDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.data.Selections
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.modeChoiceChanged
import com.github.kr328.clash.service.util.sendOverrideChanged
import com.github.kr328.clash.service.util.sessionOverrideFor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.UUID

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private val selections = Selections.queue
    private val selectionWriter = CoroutineScope(SupervisorJob() + selections)
    private var logReceiver: ReceiveChannel<LogMessage>? = null
    private var markReceiver: Job? = null

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): ProxyGroupNames {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        val result = Clash.patchSelector(group, name)

        persistSelection(group, name) {
            store.activeProfile?.let { current ->
                when (result) {
                    Clash.PatchResult.Done ->
                        SelectionDao().setSelected(Selection(current, group, name))
                    Clash.PatchResult.NoSelector ->
                        SelectionDao().removeSelected(current, group)
                    Clash.PatchResult.Failed -> Unit
                }
            }
        }

        return result == Clash.PatchResult.Done
    }

    override fun rememberSelection(group: String, name: String) {
        val current = store.activeProfile ?: return

        persistSelection(group, name) {
            SelectionDao().setSelected(Selection(current, group, name))
        }
    }

    private fun persistSelection(group: String, name: String, block: () -> Unit) {
        selectionWriter.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("Remember selection $name for $group: $e", e)
            }
        }
    }

    override suspend fun querySelections(): Map<String, String> = withContext(selections) {
        val current = store.activeProfile ?: return@withContext emptyMap()

        SelectionDao().querySelections(current).associate { it.proxy to it.selected }
    }

    override suspend fun testProfileDelays(uuid: UUID): String = withContext(Dispatchers.IO) {
        Clash.testProfileDelays(context.importedDir.resolve(uuid.toString()))
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override suspend fun queryProfileMode(): ProfileMode = withContext(Dispatchers.IO) {
        val current = store.activeProfile ?: return@withContext ProfileMode()

        Clash.queryModeOf(
            context.importedDir.resolve(current.toString()),
            sessionOverrideFor(ModeChoiceDao().queryChoice(current)),
        )
    }

    override suspend fun setProfileMode(mode: TunnelState.Mode) = withContext(Dispatchers.IO) {
        val current = store.activeProfile ?: return@withContext

        if (!modeChoiceChanged(ModeChoiceDao().queryChoice(current), mode)) return@withContext

        ModeChoiceDao().setChoice(ModeChoice(current, mode))

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
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
