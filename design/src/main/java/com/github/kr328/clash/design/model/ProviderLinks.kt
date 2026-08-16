package com.github.kr328.clash.design.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.github.kr328.clash.design.R
import com.github.kr328.clash.service.model.PanelInfo

data class ProviderLink(
    @StringRes val title: Int,
    @DrawableRes val icon: Int,
    val url: String,
)

fun providerLinks(panel: PanelInfo?): List<ProviderLink> {
    if (panel == null) return emptyList()

    return listOf(
        ProviderLink(R.string.clod_portal, R.drawable.ic_baseline_account, panel.portalUrl),
        ProviderLink(R.string.clod_support, R.drawable.ic_baseline_chat, panel.supportUrl),
        ProviderLink(R.string.clod_bot, R.drawable.ic_baseline_smart_toy, panel.botUrl),
        ProviderLink(R.string.clod_monitor, R.drawable.ic_baseline_monitor_heart, panel.monitorUrl),
        ProviderLink(R.string.clod_guide, R.drawable.ic_baseline_menu_book, panel.guideUrl),
    ).filter { it.url.isNotBlank() }
}
