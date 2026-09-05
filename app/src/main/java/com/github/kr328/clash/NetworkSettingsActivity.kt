package com.github.kr328.clash

import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeLocalProxyPort
import com.github.kr328.clash.service.util.activeTunPrefs
import com.github.kr328.clash.service.util.strictPrivateDnsHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        // Разовое чтение при открытии экрана: и стек подписки, и Private DNS читаются вне главного потока
        val profileTunStack = withContext(Dispatchers.IO) { activeTunPrefs()?.stack ?: "" }

        val design = NetworkSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
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
