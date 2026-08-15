package com.github.kr328.clash

import com.github.kr328.clash.design.DiagnosticsSettingsDesign
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class DiagnosticsSettingsActivity : BaseActivity<DiagnosticsSettingsDesign>() {
    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val design = DiagnosticsSettingsDesign(
            this,
            ServiceStore(this),
            clashRunning && uiStore.enableVpn,
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
                        DiagnosticsSettingsDesign.Request.Back -> finish()
                        DiagnosticsSettingsDesign.Request.Saved -> {
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                }
            }
        }
    }
}
