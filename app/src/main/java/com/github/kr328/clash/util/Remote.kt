package com.github.kr328.clash.util

import android.os.DeadObjectException
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.R
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.CoroutineContext

class ServiceUnavailableException(message: String) : IOException(message)

private const val REMOTE_WAIT_MS = 20_000L
private const val REMOTE_RETRY_DELAY_MS = 500L

val serviceUnavailableHandler = CoroutineExceptionHandler { _, e ->
    if (e !is ServiceUnavailableException) throw e

    Log.e("Remote service unavailable: ${e.message}")

    Handler(Looper.getMainLooper()).post {
        Toast.makeText(Global.application, e.message, Toast.LENGTH_LONG).show()
    }
}

private suspend fun awaitRemote(): IRemoteService {
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
    select: (IRemoteService) -> R,
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
): T = withRemote(context, retry, { it.clash() }, block)

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    retry: Boolean = true,
    block: suspend IProfileManager.() -> T
): T = withRemote(context, retry, { it.profile() }, block)
