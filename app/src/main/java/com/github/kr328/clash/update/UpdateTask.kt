package com.github.kr328.clash.update

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.activeLocalProxyPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

object UpdateTask {
    sealed interface State {
        data object Idle : State
        data class Checking(val manual: Boolean) : State
        data class UpToDate(val manual: Boolean) : State
        data class CheckFailed(val manual: Boolean, val reason: String?) : State
        data class Available(val available: Updater.Available) : State
        data class Downloading(val available: Updater.Available, val progress: Float) : State
        data class Ready(val available: Updater.Available, val apk: File) : State
        data class Failed(val reason: String) : State
    }

    private val current = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = current

    private var job: Job? = null

    val available: Updater.Available?
        get() = when (val value = current.value) {
            is State.Available -> value.available
            is State.Downloading -> value.available
            is State.Ready -> value.available
            else -> null
        }

    fun check(context: Context, manual: Boolean) {
        if (job?.isActive == true) return

        if (!manual && available != null) return

        val app = context.applicationContext

        current.value = State.Checking(manual)

        job = Global.launch {
            val outcome = UpdatePrompt.check(app, manual, app.activeLocalProxyPort())

            current.value = when (outcome) {
                is UpdatePrompt.Outcome.Ready -> State.Available(outcome.available)
                UpdatePrompt.Outcome.UpToDate -> State.UpToDate(manual)
                is UpdatePrompt.Outcome.Failed -> State.CheckFailed(manual, outcome.reason)
            }
        }
    }

    fun download(context: Context) {
        if (job?.isActive == true) return

        val available = available ?: return

        val app = context.applicationContext

        current.value = State.Downloading(available, -1f)

        job = Global.launch {
            val result = Updater.download(app, available, app.activeLocalProxyPort()) { received, total ->
                if (total > 0) {
                    current.value = State.Downloading(available, received.toFloat() / total)
                }
            }

            result.fold(
                onSuccess = { apk ->
                    current.value = State.Ready(available, apk)

                    try {
                        ApkInstaller.install(app, apk)

                        current.value = State.Idle
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Install update: $e", e)

                        current.value = State.Failed(e.message ?: e.toString())
                    }
                },
                onFailure = {
                    Log.w("Download update: $it", it)

                    current.value = State.Failed(it.message ?: it.toString())
                },
            )
        }
    }

    fun dismiss() {
        when (current.value) {
            is State.Checking, is State.Downloading, is State.Ready -> return
            else -> current.value = State.Idle
        }
    }
}
