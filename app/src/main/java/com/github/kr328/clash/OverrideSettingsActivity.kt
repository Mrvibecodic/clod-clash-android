package com.github.kr328.clash

import android.os.Bundle
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.ProfileMode
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.compose.screen.ModeShadow
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.profileDisplayName
import com.github.kr328.clash.util.ServiceUnavailableException
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    private var draft: PendingOverride.Draft? = null

    override suspend fun main() {
        val opened = PendingOverride.take(PendingOverride.SLOT_OVERRIDE, restored)
        val draft = opened.draft

        this.draft = draft

        val active = withProfile { queryActive() }
        val panel = active?.let { queryPanelInfo(it.uuid) }

        // Режим здесь только для подсказок (замок, тень выбора): при сбое экран
        // открывается без них — замок всё равно проверяет служба.
        val profileMode = try {
            active?.let { withClash { queryProfileMode(it.uuid) } } ?: ProfileMode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServiceUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.w("Query profile mode: $e", e)

            ProfileMode()
        }

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
            unreadable = opened.unreadable,
        )

        if (draft != null) saveOverrideOnFinish(PendingOverride.SLOT_OVERRIDE, draft, design)

        setContentDesign(design)

        if (opened.lost) {
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

        PendingOverride.put(PendingOverride.SLOT_OVERRIDE, draft, outState)
    }
}
