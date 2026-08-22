package com.github.kr328.clash.service.util

import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

const val STOP_JOIN_TIMEOUT_MILLIS = 3000L

fun CoroutineScope.cancelAndJoinBlocking(timeoutMillis: Long = STOP_JOIN_TIMEOUT_MILLIS) {
    val scope = this

    runBlocking {
        scope.coroutineContext.job.cancel()

        val finished = withTimeoutOrNull(timeoutMillis) {
            scope.coroutineContext.job.join()
        }

        if (finished == null) {
            Log.w("Stop timed out after $timeoutMillis ms")
        }
    }
}
