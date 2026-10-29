package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import com.github.kr328.clash.ToggleWidgetProvider
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.service.TunService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

suspend fun Context.isClashActiveOrStarting(): Boolean = Remote.broadcasts.activeOrStarting(
    withContext(Dispatchers.IO) { StatusClient(this@isClashActiveOrStarting).isActive() },
)

// Старт без экрана (виджет, внешнее управление): вернул запрос разрешения VPN —
// его ещё надо показать, иначе виджет уже ждёт и человеку сказано
fun Context.quickStartClash(toastDuration: Int = Toast.LENGTH_SHORT): Intent? {
    val vpnRequest = startClashService(unattended = true)

    if (vpnRequest != null) return vpnRequest

    ToggleWidgetProvider.notifyWait(this)
    Toast.makeText(this, R.string.clod_status_connecting, toastDuration).show()

    return null
}

fun Context.quickStopClash(toastDuration: Int = Toast.LENGTH_SHORT) {
    ToggleWidgetProvider.notifyWait(this)
    Remote.broadcasts.requestStop()
    Toast.makeText(this, R.string.external_control_stopped, toastDuration).show()
}
