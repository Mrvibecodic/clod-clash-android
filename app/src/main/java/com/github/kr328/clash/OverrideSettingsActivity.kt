package com.github.kr328.clash

import android.os.Bundle
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.compose.screen.ModeShadow
import com.github.kr328.clash.design.model.PendingRestore
import com.github.kr328.clash.design.model.pendingRestore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.profileDisplayName
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    private var draft: PendingOverride.Draft? = null

    override suspend fun main() {
        val pending = PendingOverride.take(PendingOverride.SLOT_OVERRIDE)
        val restore = pendingRestore(
            flagSet = restored?.getBoolean(PendingOverride.KEY) == true,
            valuePresent = pending != null,
        )

        val stored = if (pending == null) readStoredOverride() else null

        val draft = pending
            ?: (stored as? StoredOverride.Readable)?.let { PendingOverride.Draft(it.value) }

        this.draft = draft
        val service = ServiceStore(this)

        val active = withProfile { queryActive() }
        val panel = active?.let { queryPanelInfo(it.uuid) }

        val profileMode = withClash { queryProfileMode() }

        val modeLocked = profileMode.source == ProfileMode.Source.Locked

        val choice = profileMode
            .takeIf { it.source == ProfileMode.Source.Choice }
            ?.mode

        val modeShadow = if (active != null && choice != null) {
            ModeShadow(profileDisplayName(panel, active.name, active.nameManual), choice)
        } else {
            null
        }

        val design = OverrideSettingsDesign(
            this,
            draft?.value ?: ConfigurationOverride(),
            modeLocked = modeLocked,
            modeShadow = modeShadow,
            unreadable = (stored as? StoredOverride.Unreadable)?.reason,
        )

        if (draft != null) {
            val discard = {
                defer { PendingOverride.clear(PendingOverride.SLOT_OVERRIDE) }

                finish()
            }

            defer {
                if (!draft.saveReporting(design, discard)) throw FinishCancelled()

                PendingOverride.clear(PendingOverride.SLOT_OVERRIDE)
            }
        }

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
                            if (design.requestResetConfirm() && resetStoredOverride(design)) {
                                defer { }

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

        PendingOverride.put(PendingOverride.SLOT_OVERRIDE, draft)

        outState.putBoolean(PendingOverride.KEY, draft?.dirty() == true)
    }
}
