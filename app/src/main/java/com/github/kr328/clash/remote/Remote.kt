package com.github.kr328.clash.remote

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.ApkBrokenActivity
import com.github.kr328.clash.AppCrashedActivity
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.verifyApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Remote {
    val broadcasts: Broadcasts = Broadcasts(Global.application)
    val service: Service = Service(Global.application) {
        ApplicationObserver.createdActivities.forEach { it.finish() }

        val intent = AppCrashedActivity::class.intent
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        Global.application.startActivity(intent)
    }

    fun launch() {
        ApplicationObserver.attach(Global.application)

        ApplicationObserver.onVisibleChanged {
            if(it) {
                Log.d("App becomes visible")
                service.bind()
                broadcasts.register()
            }
            else {
                Log.d("App becomes invisible")
                service.requestUnbind()
            }
        }

        Global.launch(Dispatchers.IO) {
            try {
                verifyApp()
            } catch (e: Exception) {
                Log.w("Verify app: $e", e)
            }
        }
    }

    private suspend fun verifyApp() {
        val context = Global.application
        val store = AppStore(context)
        val updatedAt = getLastUpdated(context)

        if (store.updatedAt != updatedAt) {
            if (!context.verifyApk()) {
                return withContext(Dispatchers.Main) {
                    ApplicationObserver.createdActivities.forEach { it.finish() }

                    val intent = ApkBrokenActivity::class.intent
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    context.startActivity(intent)
                }
            } else {
                store.updatedAt = updatedAt
            }
        }
    }

    private fun getLastUpdated(context: Context): Long {
        return context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }
}
