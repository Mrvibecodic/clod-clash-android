package com.github.kr328.clash

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.design.NetworkSettingsPrefs
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.remote.Remote
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

        val design = NetworkSettingsDesign(
            this,
            uiStore,
            prefs,
            clashRunning,
            activeLocalProxyPort() ?: 0,
            profileTunStack,
            strictPrivateDnsHost(),
            Remote.broadcasts.diagnosticsState,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        Event.DiagnosticsStatusChanged ->
                            design.updateDiagnosticsStatus(Remote.broadcasts.diagnosticsState)
                        Event.ActivityStart -> design.refreshDiagnosticsAccess()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.Back -> finish()
                        NetworkSettingsDesign.Request.OpenDiagnostics -> {
                            val result = startActivityForResult(
                                ActivityResultContracts.StartActivityForResult(),
                                DiagnosticsSettingsActivity::class.intent,
                            )
                            if (result.resultCode == Activity.RESULT_OK) {
                                design.refreshDiagnosticsAccess()
                                design.showToast(
                                    R.string.diagnostics_credential_saved,
                                    ToastDuration.Short,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}
