package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import kotlinx.coroutines.channels.Channel

class TunStackModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val changes = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_TUN_STACK_CHANGED)
        }

        while (true) {
            changes.receive()

            enqueueEvent(Unit)
        }
    }
}
