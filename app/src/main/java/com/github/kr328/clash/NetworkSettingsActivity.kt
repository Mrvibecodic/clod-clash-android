package com.github.kr328.clash

import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.design.NetworkSettingsPrefs
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.store.ServiceSettings
import com.github.kr328.clash.service.util.activeLocalProxyPort
import com.github.kr328.clash.service.util.activeTunPrefs
import com.github.kr328.clash.service.util.readTunPrefs
import com.github.kr328.clash.service.util.strictPrivateDnsHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.UUID

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        val loaded = withContext(Dispatchers.IO) {
            StatusClient(this@NetworkSettingsActivity).status()
                .takeIf { it.running }
                ?.uuid
                ?.let { uuid -> runCatching { UUID.fromString(uuid) }.getOrNull() }
        }

        val profileTunStack = withContext(Dispatchers.IO) {
            if (loaded != null) {
                readTunPrefs(loaded)?.stack ?: ""
            } else {
                activeTunPrefs()?.stack ?: ""
            }
        }
        val prefs = ServiceSettings.access { NetworkSettingsPrefs.read(this) }
        var locked = clashActive

        val design = NetworkSettingsDesign(
            this,
            uiStore,
            prefs,
            locked,
            activeLocalProxyPort() ?: 0,
            profileTunStack,
            strictPrivateDnsHost(),
        )

        setContentDesign(design)

        fun syncLock() {
            if (clashActive != locked) {
                locked = clashActive

                design.setLocked(locked)
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        // Экран показывает подписку, которую загружает сессия, и
                        // от неё зависит только замком — тот переключается на месте.
                        Event.ClashStarting, Event.ClashStart, Event.ClashStop, Event.ActivityStart -> syncLock()
                        Event.ServiceRecreated -> recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.Back -> finish()
                    }
                }
            }
        }
    }

}
