package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class SuspendModule(service: Service) : Module<Unit>(service) {
    private val store = ServiceStore(service)

    override suspend fun run() {
        if (store.keepAwake) {
            runKeepAwake()
        } else {
            runSuspendOnScreenOff()
        }
    }

    private suspend fun runKeepAwake() {
        val wakeLock = service.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${service.packageName}:keep_awake")
            ?.apply { setReferenceCounted(false) }

        Clash.suspendCore(false)

        try {
            wakeLock?.acquire()

            Log.d("Clash keep awake")

            awaitCancellation()
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    private suspend fun runSuspendOnScreenOff() {
        val interactive = service.getSystemService<PowerManager>()?.isInteractive ?: true

        Clash.suspendCore(!interactive)

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        try {
            while (true) {
                when (screenToggle.receive().action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Clash.suspendCore(false)

                        Log.d("Screen on: core keeps running")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Clash.suspendCore(true)

                        Log.d("Screen off: core keeps running")
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                Clash.suspendCore(false)
            }
        }
    }
}
