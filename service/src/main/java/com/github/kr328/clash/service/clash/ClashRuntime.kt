package com.github.kr328.clash.service.clash

import android.os.SystemClock
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.service.clash.module.Module
import com.github.kr328.clash.service.clash.module.ReactionSeries
import com.github.kr328.clash.service.freeze.NetworkKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val globalLock = Mutex()

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(module: T): T
}

// Перезапускать ли упавший модуль, решает сессия (true — перезапустить): она
// знает, жива ли и своя ли. live — модуль ещё не отменён
typealias ModuleFailureSink = (module: String, e: Exception, live: Boolean, tryRestart: () -> Boolean) -> Boolean

interface ClashRuntime {
    fun launch(onModuleFailure: ModuleFailureSink)
    fun requestGc()
}

private class ModuleFailure(cause: Exception) : Exception(cause)

internal class RestartBudget(limit: Int, windowMs: Long, private val now: () -> Long) {
    private val restarts = ReactionSeries(windowMs, limit)

    @Synchronized
    fun take(): Boolean = restarts.take(now())
}

internal fun restartBudget() = RestartBudget(2, 10 * 60 * 1000L, SystemClock::elapsedRealtime)

// Отказ сообщается до того, как отмена дойдёт до тела рантайма: тело видит
// только отмену, а причину остановки должна знать сессия
internal suspend fun runModule(
    name: String,
    restarts: RestartBudget?,
    onFailure: ModuleFailureSink,
    restartDelayMs: Long = RESTART_DELAY_MS,
    execute: suspend () -> Unit,
) {
    while (true) {
        try {
            return execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val live = currentCoroutineContext().isActive

            if (!onFailure(name, e, live) { restarts?.take() == true }) throw ModuleFailure(e)

            // Сбой системы (перезапуск её служб, обрыв binder) мгновенно
            // повторился бы и съел бюджет
            delay(restartDelayMs)
        }
    }
}

private const val RESTART_DELAY_MS = 3_000L

fun CoroutineScope.clashRuntime(block: suspend ClashRuntimeScope.() -> Unit): ClashRuntime {
    return object : ClashRuntime {
        override fun launch(onModuleFailure: ModuleFailureSink) {
            launch(Dispatchers.IO) {
                globalLock.withLock {
                    Log.d("ClashRuntime: initialize")

                    ServiceLog.mark("runtime: start")

                    try {
                        Clash.reset()
                        clearSessionOverride()

                        // Мобильная ли сеть — ядру до модулей: первая загрузка подписки
                        // уже без серверов только для мобильной сети вне неё. Дальше её
                        // называет сторож сети, и его слово — последнее
                        Clash.setCellular(NetworkKey.cellularNow(Global.application))

                        // Сброс ядра и снятие замка ждут, пока модули доработают
                        // свои finally: иначе прощание старой сессии (пустой DNS,
                        // остановка туннеля) ложится поверх следующей.
                        coroutineScope {
                            val scope = object : ClashRuntimeScope {
                                override fun <E, T : Module<E>> install(module: T): T {
                                    launch {
                                        runModule(
                                            module.javaClass.simpleName,
                                            restartBudget().takeIf { module.restartable },
                                            onModuleFailure,
                                            execute = module::execute,
                                        )
                                    }

                                    return module
                                }
                            }

                            scope.block()

                            cancel()
                        }
                    } catch (e: ModuleFailure) {
                        // Причина уже у сессии; выпущенный наружу, отказ уронил бы
                        // процесс службы
                    } finally {
                        withContext(NonCancellable) {
                            val startedAt = SystemClock.elapsedRealtime()

                            Clash.reset()
                            clearSessionOverride()

                            Log.i("ClashRuntime: destroyed in ${SystemClock.elapsedRealtime() - startedAt} ms")

                            ServiceLog.mark("runtime: finished")
                        }
                    }
                }
            }
        }

        override fun requestGc() {
            Clash.forceGc()
        }
    }
}

// Сброс сессионных настроек не проваливается, кроме как паникой в мосте; старт и
// остановка рантайма из-за неё срываться не должны.
private fun clearSessionOverride() {
    try {
        Clash.clearOverride(Clash.OverrideSlot.Session)
    } catch (e: Exception) {
        Log.w("Clear session override: $e", e)
    }
}
