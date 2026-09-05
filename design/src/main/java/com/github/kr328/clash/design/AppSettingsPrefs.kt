package com.github.kr328.clash.design

import com.github.kr328.clash.service.store.ServiceStore

data class AppSettingsPrefs(
    val dynamicNotification: Boolean,
    val enableHwid: Boolean,
    val subNotifications: Boolean,
    val profileErrorNotifications: Boolean,
    val profileUpdateNotifications: Boolean,
) {
    companion object {
        fun read(store: ServiceStore): AppSettingsPrefs = AppSettingsPrefs(
            dynamicNotification = store.dynamicNotification,
            enableHwid = store.enableHwid,
            subNotifications = store.enableSubNotifications,
            profileErrorNotifications = store.notifyProfileErrors,
            profileUpdateNotifications = store.notifyProfileUpdates,
        )
    }
}
