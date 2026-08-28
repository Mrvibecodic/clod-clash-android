package com.github.kr328.clash.service.util

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.model.DiagnosticsLogEvent
import com.github.kr328.clash.common.model.DiagnosticsMode
import com.github.kr328.clash.common.model.DiagnosticsState
import java.util.*

fun Context.sendBroadcastSelf(intent: Intent) {
    sendBroadcast(
        intent.setPackage(this.packageName),
        Permissions.RECEIVE_SELF_BROADCASTS
    )
}

fun Context.sendControlBroadcastSelf(intent: Intent) {
    sendBroadcastSelf(intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
}

fun Context.sendProfileChanged(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_CHANGED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileLoaded(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_LOADED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileUpdateCompleted(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileUpdateFailed(uuid: UUID, reason: String) {
    val intent = Intent(Intents.ACTION_PROFILE_UPDATE_FAILED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())
        .putExtra(Intents.EXTRA_FAIL_REASON, reason)

    sendBroadcastSelf(intent)
}

fun Context.sendOverrideChanged() {
    val intent = Intent(Intents.ACTION_OVERRIDE_CHANGED)

    sendBroadcastSelf(intent)
}

fun Context.sendDiagnosticsChanged(mode: DiagnosticsMode, event: DiagnosticsLogEvent? = null) {
    val commandId = DiagnosticsModeCommandStore(this).append(mode, event)
    if (commandId == null) {
        DiagnosticsEventJournal(this).append(DiagnosticsLogEvent.UiModeCommandStoreFailed)
    }
    Log.i("[Diagnostics] event=${event?.wireName ?: "mode_changed"} result=broadcast mode=${mode.name.lowercase()}")
    sendBroadcastSelf(
        Intent(Intents.ACTION_DIAGNOSTICS_CHANGED)
            .putExtra(Intents.EXTRA_DIAGNOSTICS_MODE, mode.name)
            .putExtra(Intents.EXTRA_DIAGNOSTICS_MODE_COMMAND_ID, commandId)
            .putExtra(Intents.EXTRA_DIAGNOSTICS_LOG_EVENT, event?.code ?: -1)
    )
}

fun Context.sendDiagnosticsLogEvent(event: DiagnosticsLogEvent) {
    Log.i("[Diagnostics] event=${event.wireName} result=broadcast")
    sendBroadcastSelf(
        Intent(Intents.ACTION_DIAGNOSTICS_LOG_EVENT)
            .putExtra(Intents.EXTRA_DIAGNOSTICS_LOG_EVENT, event.code)
    )
}

fun Context.sendDiagnosticsStatus(status: DiagnosticsState) {
    sendBroadcastSelf(
        Intent(Intents.ACTION_DIAGNOSTICS_STATUS)
            .putExtra(Intents.EXTRA_DIAGNOSTICS_STATUS, status.name)
    )
}

fun Context.sendServiceRecreated() {
    ServiceLog.mark("broadcast: service recreated")

    sendControlBroadcastSelf(Intent(Intents.ACTION_SERVICE_RECREATED))
}

fun Context.sendClashStarted() {
    ServiceLog.mark("broadcast: clash started")

    sendControlBroadcastSelf(Intent(Intents.ACTION_CLASH_STARTED))
}

fun Context.sendClashStopped(reason: String?) {
    ServiceLog.mark("broadcast: clash stopped, with reason = ${reason != null}")

    sendControlBroadcastSelf(
        Intent(Intents.ACTION_CLASH_STOPPED).putExtra(
            Intents.EXTRA_STOP_REASON,
            reason
        )
    )
}
