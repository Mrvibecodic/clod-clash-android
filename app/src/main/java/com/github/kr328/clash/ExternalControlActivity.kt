package com.github.kr328.clash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.HumanMessage
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.ProfileFields
import com.github.kr328.clash.service.util.humanizeUpdateFailure
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.util.withAppLocale
import com.github.kr328.clash.util.serviceUnavailableHandler

open class ExternalControlActivity : Activity(), CoroutineScope by (MainScope() + serviceUnavailableHandler) {
    protected open fun controlAllowed(): Boolean = UiStore(this).allowExternalControl

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return finish()
            val url = uri.getQueryParameter("url") ?: return finish()

            if (Uri.parse(url).scheme?.lowercase(Locale.ROOT) != "https") {
                return finish()
            }

            if (url.length > ProfileFields.SOURCE_MAX) {
                return finish()
            }

            launch {
                try {
                    withContext(NonCancellable) {
                        val uuid = withProfile(retry = false) {
                            val linkName = uri.getQueryParameter("name")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?.take(MAX_NAME_LENGTH)
                            val name = linkName ?: getString(R.string.new_profile)

                            val parsedInterval = uri.getQueryParameter("update-interval")?.toLongOrNull() ?: 0L
                            val updateInterval = if (parsedInterval > 0) parsedInterval.coerceAtLeast(15L) else 0L
                            val intervalMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(updateInterval)

                            // Канал выберет сохранение: сначала защищённый, без него — обычный путь.
                            // Интервал из ссылки — от панели, не выбор человека: его
                            // сменит то, что панель пришлёт при обновлении
                            create(Profile.Type.Url, name, url, interval = intervalMs, nameManual = linkName != null)
                        }

                        val opened = !isFinishing && !isDestroyed && runCatching {
                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }.isSuccess

                        if (!opened) {
                            withProfile(retry = false) { release(uuid) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Add subscription from a link: $e", e)

                    val raw = e.message.orEmpty()
                    val reason = humanizeUpdateFailure(raw)
                        ?: raw.takeIf { e is HumanMessage && it.isNotBlank() }
                        ?: Redact.text(raw).ifBlank { e.javaClass.name }

                    Toast.makeText(applicationContext, getString(R.string.clod_sub_add_failed, reason), Toast.LENGTH_LONG).show()
                } finally {
                    finish()
                }
            }

            return
        }

        launch {
            handleControl()

            if (isFinishing || isDestroyed) return@launch

            finish()
        }
    }

    override fun onDestroy() {
        cancel()

        super.onDestroy()
    }

    private suspend fun handleControl() {
        if (isFinishing || isDestroyed) return

        when (intent.action) {
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
                if (isFinishing || isDestroyed) return

                Toast.makeText(this, R.string.external_control_started, Toast.LENGTH_LONG).show()
            } else {
                startClash()
            }

            Intents.ACTION_STOP_CLASH -> if (!controlAllowed()) {
                refuseControl()
            } else if (isClashRunning()) {
                stopClash()
            } else {
                if (isFinishing || isDestroyed) return

                Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refuseControl() {
        if (isFinishing || isDestroyed) return

        Toast.makeText(this, R.string.clod_external_control_refused, Toast.LENGTH_LONG).show()
    }

    private suspend fun isClashRunning(): Boolean = Remote.broadcasts.activeOrStarting(
        withContext(Dispatchers.IO) { StatusClient(this@ExternalControlActivity).isActive() },
    )

    private fun startClash() {
        if (isFinishing || isDestroyed) return

        // Нет разрешения на VPN — то же окно разрешения, что у плитки и виджета
        if (startClashService(unattended = true) != null) {
            startActivity(WidgetToggleActivity::class.intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        ToggleWidgetProvider.notifyWait(this)
        Toast.makeText(this, R.string.clod_status_connecting, Toast.LENGTH_LONG).show()
    }

    private fun stopClash() {
        if (isFinishing || isDestroyed) return

        ToggleWidgetProvider.notifyWait(this)
        Remote.broadcasts.requestStop()
        Toast.makeText(this, R.string.external_control_stopped, Toast.LENGTH_LONG).show()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val MAX_NAME_LENGTH = 128
    }
}
