package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.*
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.util.withAppLocale

open class ExternalControlActivity : Activity(), CoroutineScope by MainScope() {
    protected open fun controlAllowed(): Boolean = UiStore(this).allowExternalControl

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        when(intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return finish()
                val url = uri.getQueryParameter("url") ?: return finish()

                launch {
                    val uuid = withProfile {
                        val type = when (uri.getQueryParameter("type")?.lowercase(Locale.getDefault())) {
                            "url" -> Profile.Type.Url
                            "file" -> Profile.Type.File
                            else -> Profile.Type.Url
                        }
                        val name = uri.getQueryParameter("name") ?: getString(R.string.new_profile)

                        val parsedInterval = uri.getQueryParameter("update-interval")?.toLongOrNull() ?: 0L
                        val updateInterval = if (parsedInterval > 0) parsedInterval.coerceAtLeast(15L) else 0L
                        val intervalMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(updateInterval)

                        create(type, name).also {
                            patch(it, name, url, intervalMs, null)
                        }
                    }
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                    finish()
                }
                return
            }

            Intents.ACTION_TOGGLE_CLASH -> if (!controlAllowed()) {
                refuseControl()
            } else if (isClashRunning()) {
                stopClash()
            } else {
                startClash()
            }

            Intents.ACTION_START_CLASH -> if (!controlAllowed()) {
                refuseControl()
            } else if (isClashRunning()) {
                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
            } else {
                startClash()
            }

            Intents.ACTION_STOP_CLASH -> if (!controlAllowed()) {
                refuseControl()
            } else if (isClashRunning()) {
                stopClash()
            }
        }
        return finish()
    }

    private fun refuseControl() {
        Toast.makeText(this, R.string.clod_external_control_refused, Toast.LENGTH_LONG).show()
    }

    private fun isClashRunning(): Boolean {
        return StatusClient(this).isRunning()
    }

    private fun startClash() {
        val vpnRequest = startClashService()
        if (vpnRequest != null) {
            startActivity(MainActivity::class.intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        ToggleWidgetProvider.notifyWait(this)
        Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
    }

    private fun stopClash() {
        ToggleWidgetProvider.notifyWait(this)
        stopClashService()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
