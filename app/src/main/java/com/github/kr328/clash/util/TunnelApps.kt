package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.service.model.TunPrefs
import com.github.kr328.clash.service.store.ServiceSettings
import com.github.kr328.clash.service.util.onlySelfThroughTunnel
import com.github.kr328.clash.service.util.readTunPrefs
import com.github.kr328.clash.service.util.tunApps
import com.github.kr328.clash.service.util.uidLookup
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Состав туннеля, который служба соберёт из нынешних настроек и списков
// подписки loaded (без неё — активной). Подписки нет — туннеля не будет.
suspend fun Context.plannedOnlySelf(loaded: UUID?): Boolean {
    val (profile, mode, selected) = ServiceSettings.access {
        Triple(loaded ?: activeProfile, accessControlMode, accessControlPackages)
    }

    profile ?: return false

    return withContext(Dispatchers.IO) {
        val apps = tunApps(mode, selected, readTunPrefs(profile) ?: TunPrefs(), packageName, packageManager.uidLookup())

        onlySelfThroughTunnel(apps.allowed.keys, packageName)
    }
}
