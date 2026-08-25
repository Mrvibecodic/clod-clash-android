package com.github.kr328.clash.design.store

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.model.AppInfoSort
import com.github.kr328.clash.design.model.DarkMode
import java.util.UUID

class UiStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    private val store = Store(preferences.asStoreProvider())

    fun reset() {
        val editor = preferences.edit()

        SETTING_KEYS.forEach { editor.remove(it) }

        editor.apply()
    }

    var enableVpn: Boolean by store.boolean(
        key = "enable_vpn",
        defaultValue = true
    )

    var showGroupIcons: Boolean by store.boolean(
        key = "show_group_icons",
        defaultValue = true
    )

    var darkMode: DarkMode by store.enum(
        key = "dark_mode",
        defaultValue = DarkMode.Auto,
        values = DarkMode.values()
    )

    var hideAppIcon: Boolean by store.boolean(
        key = "hide_app_icon",
        defaultValue = context.packageManager.getComponentEnabledSetting(context.mainActivityAlias)
            .let { state ->
                state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                        state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            },
    )

    var hideFromRecents: Boolean by store.boolean(
        key = "hide_from_recents",
        defaultValue = false,
    )

    var notificationsAsked: Boolean by store.boolean(
        key = "notifications_asked",
        defaultValue = false,
    )

    var allowExternalControl: Boolean by store.boolean(
        key = "allow_external_control",
        defaultValue = false,
    )

    var reliabilityAsked: Boolean by store.boolean(
        key = "reliability_asked",
        defaultValue = false,
    )

    fun favorites(profile: UUID): Set<String> {
        return store.provider.getStringSet(favoritesKey(profile), emptySet())
    }

    fun setFavorites(profile: UUID, favorites: Set<String>) {
        store.provider.setStringSet(favoritesKey(profile), favorites)
    }

    private fun favoritesKey(profile: UUID): String = "favorites_$profile"

    var proxySort: ProxySort by store.enum(
        key = "proxy_sort",
        defaultValue = ProxySort.Default,
        values = ProxySort.values()
    )

    var accessControlSort: AppInfoSort by store.enum(
        key = "access_control_sort",
        defaultValue = AppInfoSort.Label,
        values = AppInfoSort.values(),
    )

    var accessControlReverse: Boolean by store.boolean(
        key = "access_control_reverse",
        defaultValue = false
    )

    var accessControlSystemApp: Boolean by store.boolean(
        key = "access_control_system_app",
        defaultValue = false,
    )

    companion object {
        private const val PREFERENCE_NAME = "ui"

        private val SETTING_KEYS = listOf(
            "enable_vpn",
            "dark_mode",
            "show_group_icons",
            "hide_app_icon",
            "hide_from_recents",
            "allow_external_control",
            "proxy_sort",
            "proxy_last_group",
            "access_control_sort",
            "access_control_reverse",
            "access_control_system_app",
        )

        val Context.mainActivityAlias: ComponentName
            get() = ComponentName(this, "com.github.kr328.clash.MainActivityAlias")
    }
}
