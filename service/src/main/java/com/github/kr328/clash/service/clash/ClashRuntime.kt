package com.github.kr328.clash.service.clash

import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.service.clash.module.Module
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val globalLock = Mutex()

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(module: T): T
}

// Отказ модуля: имя модуля, исключение, live — модуль ещё не отменён
typealias ModuleFailureSink = (String, Exception, Boolean) -> Unit

interface ClashRuntime {
    fun launch(onModuleFailure: ModuleFailureSink)
    fun requestGc()
}

private class ModuleFailure(cause: Exception) : Exception(cause)

// Отказ модуля сообщается сразу, до того как отмена дойдёт до тела рантайма:
// тело видит только отмену, а причину остановки должна знать сессия. Отказ
// уже отменённого модуля (сворачивание) — следствие остановки, не причина
internal suspend fun runModule(name: String, onFailure: ModuleFailureSink, execute: suspend () -> Unit) {
    try {
        execute()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(name, e, currentCoroutineContext().isActive)

        throw ModuleFailure(e)
    }
}

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

                        // Сброс ядра и снятие замка ждут, пока модули доработают
                        // свои finally: иначе прощание старой сессии (пустой DNS,
                        // остановка туннеля) ложится поверх следующей.
                        coroutineScope {
                            val scope = object : ClashRuntimeScope {
                                override fun <E, T : Module<E>> install(module: T): T {
                                    launch {
                                        runModule(module.javaClass.simpleName, onModuleFailure, module::execute)
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
