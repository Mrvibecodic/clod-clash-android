package com.github.kr328.clash.design.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// Вопрос на экране с ответом T: request ждёт ответа, пока вопрос показан (shown(true)),
// отмена ожидания его убирает.
class Confirmation<T>(private val shown: (Boolean) -> Unit) {
    private var pending: CancellableContinuation<T>? = null

    suspend fun request(): T = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            pending = continuation

            shown(true)

            continuation.invokeOnCancellation {
                pending = null

                shown(false)
            }
        }
    }

    fun resume(answer: T) {
        shown(false)

        val continuation = pending ?: return

        pending = null

        if (continuation.isActive) {
            continuation.resumeWith(Result.success(answer))
        }
    }
}
