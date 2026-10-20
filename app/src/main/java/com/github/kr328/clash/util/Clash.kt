package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.service.TunService

fun Context.startClashService(unattended: Boolean = false): Intent? {
    if (UiStore(this).enableVpn) {
        val vpnRequest = VpnService.prepare(this)
        if (vpnRequest != null)
            return vpnRequest

        startForegroundServiceCompat(TunService::class.intent.putExtra(Intents.EXTRA_UNATTENDED, unattended))
    } else {
        startForegroundServiceCompat(ClashService::class.intent.putExtra(Intents.EXTRA_UNATTENDED, unattended))
    }

    // Новый старт с любого входа отменяет стоп, отложенный от прежнего
    Remote.broadcasts.startRequested()

    return null
}
