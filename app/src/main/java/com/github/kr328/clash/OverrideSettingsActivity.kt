package com.github.kr328.clash

import android.os.Bundle
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.model.PendingRestore
import com.github.kr328.clash.design.model.pendingRestore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    private var configuration: ConfigurationOverride? = null

    override suspend fun main() {
        val pending = PendingOverride.take(PendingOverride.SLOT_OVERRIDE)
        val restore = pendingRestore(
            flagSet = restored?.getBoolean(PendingOverride.KEY) == true,
            valuePresent = pending != null,
        )

        val configuration = pending
            ?: withClash { queryOverride(Clash.OverrideSlot.Persist) }

        this.configuration = configuration
        val service = ServiceStore(this)

        val modeLocked = withProfile { queryActive() }
            ?.let { queryPanelInfo(it.uuid)?.lockMode } == true

        if (modeLocked) {
            configuration.mode = null
        }

        defer {
            withClash {
                patchOverride(Clash.OverrideSlot.Persist, configuration)
            }

            PendingOverride.clear(PendingOverride.SLOT_OVERRIDE)
        }

        val design = OverrideSettingsDesign(
            this,
            configuration,
            modeLocked = modeLocked,
        )

        setContentDesign(design)

        if (restore == PendingRestore.UseStoredAndWarn) {
            design.showToast(
                com.github.kr328.clash.design.R.string.clod_override_pending_lost,
                ToastDuration.Long,
            )
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        OverrideSettingsDesign.Request.Back -> finish()
                        OverrideSettingsDesign.Request.ResetOverride -> {
                            if (design.requestResetConfirm()) {
                                defer {
                                    withClash {
                                        clearOverride(Clash.OverrideSlot.Persist)
                                    }

                                    PendingOverride.clearAll()
                                }

                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        PendingOverride.put(PendingOverride.SLOT_OVERRIDE, configuration)

        outState.putBoolean(PendingOverride.KEY, configuration != null)
    }
}
