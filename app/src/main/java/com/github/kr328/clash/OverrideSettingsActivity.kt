package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.os.BundleCompat
import com.github.kr328.clash.common.compat.getDrawableCompat
import com.github.kr328.clash.common.constants.Metadata
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.OverrideSettingsDesign
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.toAppInfo
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.queryPanelInfo
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class OverrideSettingsActivity : BaseActivity<OverrideSettingsDesign>() {
    private var configuration: ConfigurationOverride? = null

    override suspend fun main() {
        val configuration = restored
            ?.let { BundleCompat.getParcelable(it, "override", ConfigurationOverride::class.java) }
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
        }

        val design = OverrideSettingsDesign(
            this,
            configuration,
            modeLocked = modeLocked,
        )

        setContentDesign(design)

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

        configuration?.let { outState.putParcelable("override", it) }
    }
}
