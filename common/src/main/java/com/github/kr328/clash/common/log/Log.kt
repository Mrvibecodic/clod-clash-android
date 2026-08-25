package com.github.kr328.clash.common.log

import com.github.kr328.clash.common.util.Redact

object Log {
    private const val TAG = "ClodClash"

    fun i(message: String, throwable: Throwable? = null) =
        android.util.Log.i(TAG, compose(message, throwable))

    fun w(message: String, throwable: Throwable? = null) =
        android.util.Log.w(TAG, compose(message, throwable))

    fun e(message: String, throwable: Throwable? = null) =
        android.util.Log.e(TAG, compose(message, throwable))

    fun d(message: String, throwable: Throwable? = null) =
        android.util.Log.d(TAG, compose(message, throwable))

    private fun compose(message: String, throwable: Throwable?): String {
        val stack = throwable?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""

        return Redact.text(message + stack)
    }
}
