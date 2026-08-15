package com.github.kr328.clash

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.activeLocalProxyPort
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {
    override suspend fun main() {
        val design = NetworkSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            clashRunning,
            activeLocalProxyPort() ?: 0,
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
