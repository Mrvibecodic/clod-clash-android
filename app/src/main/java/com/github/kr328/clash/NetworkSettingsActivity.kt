package com.github.kr328.clash

import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.design.NetworkSettingsPrefs
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.store.ServiceStore
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
        val srvStore = ServiceStore(this)

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
        val prefs = withContext(Dispatchers.IO) { NetworkSettingsPrefs.read(srvStore) }

        val design = NetworkSettingsDesign(
            this,
            uiStore,
            srvStore,
            prefs,
            clashRunning,
            activeLocalProxyPort() ?: 0,
            profileTunStack,
            strictPrivateDnsHost(),
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
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
