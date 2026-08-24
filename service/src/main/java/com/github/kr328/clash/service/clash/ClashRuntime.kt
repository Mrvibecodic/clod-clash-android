package com.github.kr328.clash.service.clash

import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.clash.module.Module
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val globalLock = Mutex()

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(module: T): T
}

interface ClashRuntime {
    fun launch()
    fun requestGc()
}

fun CoroutineScope.clashRuntime(block: suspend ClashRuntimeScope.() -> Unit): ClashRuntime {
    return object : ClashRuntime {
        override fun launch() {
            launch(Dispatchers.IO) {
                globalLock.withLock {
                    Log.d("ClashRuntime: initialize")

                    try {
                        val modules = mutableListOf<Module<*>>()

                        Clash.reset()
                        Clash.clearOverride(Clash.OverrideSlot.Session)

                        val scope = object : ClashRuntimeScope {
                            override fun <E, T : Module<E>> install(module: T): T {
                                launch {
                                    modules.add(module)

                                    module.execute()
                                }

                                return module
                            }
                        }

                        scope.block()

                        cancel()
                    } finally {
                        withContext(NonCancellable) {
                            val startedAt = SystemClock.elapsedRealtime()

                            Clash.reset()
                            Clash.clearOverride(Clash.OverrideSlot.Session)

                            Log.i("ClashRuntime: destroyed in ${SystemClock.elapsedRealtime() - startedAt} ms")
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
