package com.github.kr328.clash.update

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.util.activeLocalProxyPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object UpdateTask {
    sealed interface State {
        data object Idle : State
        data class Checking(val manual: Boolean) : State
        data class UpToDate(val manual: Boolean) : State
        data class CheckFailed(
            val manual: Boolean,
            val kind: Updater.UpdateException.Kind?,
            val detail: String?,
        ) : State
        data class Available(val available: Updater.Available) : State
        data class Downloading(val available: Updater.Available, val progress: Float) : State
        data class Ready(val available: Updater.Available, val apk: File) : State
        data class Failed(val kind: Updater.UpdateException.Kind?, val detail: String?) : State
    }

    sealed interface InstallOutcome {
        data object Installed : InstallOutcome
        data object Returned : InstallOutcome
        data class Refused(val detail: String?) : InstallOutcome
    }

    internal fun afterInstall(state: State, outcome: InstallOutcome): State {
        if (state !is State.Ready) return state

        return when (outcome) {
            InstallOutcome.Installed -> State.Idle
            InstallOutcome.Returned -> State.Available(state.available)
            is InstallOutcome.Refused -> State.Failed(null, outcome.detail)
        }
    }

    internal fun afterCancel(state: State): State = when (state) {
        is State.Checking -> State.Idle
        is State.Downloading -> State.Available(state.available)
        else -> state
    }

    private val current = MutableStateFlow<State>(State.Idle)

    val state: StateFlow<State> = current

    private var job: Job? = null

    private var downloading: Job? = null

    val available: Updater.Available?
        get() = when (val value = current.value) {
            is State.Available -> value.available
            is State.Downloading -> value.available
            is State.Ready -> value.available
            else -> null
        }

    fun check(context: Context, manual: Boolean) {
        if (job?.isActive == true) return

        if (!manual && current.value.let { it is State.Available || it is State.Downloading }) return

        val app = context.applicationContext

        current.value = State.Checking(manual)

        job = Global.launch {
            val outcome = UpdatePrompt.check(app, manual, app.activeLocalProxyPort())

            ensureActive()

            val next = when (outcome) {
                is UpdatePrompt.Outcome.Ready -> State.Available(outcome.available)
                UpdatePrompt.Outcome.UpToDate -> State.UpToDate(manual)
                is UpdatePrompt.Outcome.Failed -> State.CheckFailed(manual, outcome.kind, outcome.detail)
            }

            current.update { if (it is State.Checking) next else it }
        }
    }

    fun download(context: Context) {
        if (job?.isActive == true) return

        val available = available ?: return

        val app = context.applicationContext

        current.value = State.Downloading(available, -1f)

        val previous = downloading

        job = Global.launch {
            withContext(NonCancellable) { previous?.join() }

            ensureActive()

            val result = Updater.download(app, available, app.activeLocalProxyPort()) { received, total ->
                if (total > 0 && isActive) {
                    current.update {
                        if (it is State.Downloading) State.Downloading(available, received.toFloat() / total) else it
                    }
                }
            }

            result.fold(
                onSuccess = { apk ->
                    val before = current.getAndUpdate {
                        if (it is State.Downloading) State.Ready(available, apk) else it
                    }

                    if (before !is State.Downloading) {
                        apk.delete()

                        return@launch
                    }

                    try {
                        ApkInstaller.install(app, apk)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("Install update: $e", e)

                        current.value = State.Failed(null, e.message ?: e.toString())
                    }
                },
                onFailure = {
                    Log.w("Download update: $it", it)

                    val kind = (it as? Updater.UpdateException)?.kind

                    current.update { state ->
                        if (state is State.Downloading) State.Failed(kind, if (kind == null) it.message ?: it.toString() else null) else state
                    }
                },
            )
        }

        downloading = job
    }

    fun cancel() {
        val before = current.getAndUpdate(::afterCancel)

        if (before is State.Checking || before is State.Downloading) job?.cancel()
    }

    fun installFinished(outcome: InstallOutcome) {
        current.update { afterInstall(it, outcome) }
    }

    fun dismiss() {
        when (current.value) {
            is State.Checking, is State.Downloading, is State.Ready -> return
            else -> current.value = State.Idle
        }
    }
}
