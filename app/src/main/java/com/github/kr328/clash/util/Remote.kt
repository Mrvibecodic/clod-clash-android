package com.github.kr328.clash.util

import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.HumanMessage
import com.github.kr328.clash.design.R
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.RemoteHandle
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class ServiceUnavailableException(message: String) : IOException(message), HumanMessage

private const val REMOTE_WAIT_MS = 20_000L
private const val REMOTE_RETRY_DELAY_MS = 500L

val serviceUnavailableHandler = CoroutineExceptionHandler { _, e ->
    if (e !is ServiceUnavailableException) throw e

    Log.e("Remote service unavailable: ${e.message}")

    Handler(Looper.getMainLooper()).post {
        Toast.makeText(Global.application, e.message, Toast.LENGTH_LONG).show()
    }
}

private suspend fun awaitRemote(): RemoteHandle {
    while (true) {
        withTimeoutOrNull(REMOTE_WAIT_MS) { Remote.service.remote.get() }?.let { return it }

        val since = Remote.service.boundSince

        if (since != 0L && System.currentTimeMillis() - since >= REMOTE_WAIT_MS) {
            throw ServiceUnavailableException(
                Global.application.withAppLocale().getString(R.string.clod_service_unavailable),
            )
        }
    }
}

private suspend fun <R, T> withRemote(
    context: CoroutineContext,
    retry: Boolean,
    select: (RemoteHandle) -> R,
    block: suspend R.() -> T,
): T {
    while (true) {
        val remote = awaitRemote()

        Remote.service.beginOperation()

        try {
            return withContext(context) { select(remote).block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)

            if (!retry) {
                throw ServiceUnavailableException(
                    Global.application.withAppLocale().getString(R.string.clod_service_unavailable),
                )
            }

            delay(REMOTE_RETRY_DELAY_MS)
        } finally {
            Remote.service.endOperation()
        }
    }
}

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    retry: Boolean = true,
    block: suspend IClashManager.() -> T
): T = withRemote(context, retry, { it.clash }, block)

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    retry: Boolean = true,
    block: suspend IProfileManager.() -> T
): T = withRemote(context, retry, { it.profile }, block)

// Работа из нескольких вызовов держит службу целиком, а не каждый вызов по
// отдельности: иначе привязка, отпущенная между вызовами (приложение ушло с
// экрана), остановила бы следующий шаг до возвращения человека в приложение.
fun launchHoldingService(block: suspend CoroutineScope.() -> Unit): Job {
    Remote.service.beginOperation()

    return Global.launch(block = block).apply {
        invokeOnCompletion { Remote.service.endOperation() }
    }
}
