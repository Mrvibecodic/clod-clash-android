package com.github.kr328.clash.design.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// Вопрос «да/нет» на экране: request ждёт ответа, пока вопрос показан (shown(true)),
// отмена ожидания его убирает.
class Confirmation(private val shown: (Boolean) -> Unit) {
    private var pending: CancellableContinuation<Boolean>? = null

    suspend fun request(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            pending = continuation

            shown(true)

            continuation.invokeOnCancellation {
                pending = null

                shown(false)
            }
        }
    }

    fun resume(confirmed: Boolean) {
        shown(false)

        val continuation = pending ?: return

        pending = null

        if (continuation.isActive) {
            continuation.resumeWith(Result.success(confirmed))
        }
    }
}
