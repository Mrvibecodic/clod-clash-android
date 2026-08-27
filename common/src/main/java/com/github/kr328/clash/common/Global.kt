package com.github.kr328.clash.common

import android.app.Application
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object Global : CoroutineScope by CoroutineScope(
    Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        Log.e("Global coroutine failed: $e", e)
    }
) {
    val application: Application
        get() = application_

    private lateinit var application_: Application

    fun init(application: Application) {
        this.application_ = application
    }
}
