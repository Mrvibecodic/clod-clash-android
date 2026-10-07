package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.awaitCancellation

// Держит процессор с настройкой «не засыпать». Ядро по экрану не усыпляется
// вовсе: tunnel.OnSuspend ядра отказывает новым TCP-соединениям и роняет UDP,
// пока экран выключен, — звать его (и OnRunning) нельзя
class SuspendModule(service: Service) : Module<Unit>(service) {
    private val store = ServiceStore(service)

    override suspend fun run() {
        if (!store.keepAwake) awaitCancellation()

        val wakeLock = service.getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${service.packageName}:keep_awake")
            ?.apply { setReferenceCounted(false) }

        try {
            wakeLock?.acquire()

            Log.i("Clash keep awake")

            awaitCancellation()
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }
}
