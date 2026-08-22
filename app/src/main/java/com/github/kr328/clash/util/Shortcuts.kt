package com.github.kr328.clash.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.ExternalControlActivity
import com.github.kr328.clash.R
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.design.R as DesignR

fun Context.applyDynamicShortcuts(hide: Boolean) {
    if (hide) {
        ShortcutManagerCompat.removeAllDynamicShortcuts(this)

        return
    }

    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
        Intent.FLAG_ACTIVITY_NO_ANIMATION

    val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
        .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
        .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
        .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
        .setIntent(
            Intent(Intents.ACTION_TOGGLE_CLASH)
                .setClassName(this, ExternalControlActivity::class.java.name)
                .addFlags(flags)
        )
        .setRank(0)
        .build()

    val start = ShortcutInfoCompat.Builder(this, "start_clash")
        .setShortLabel(getString(DesignR.string.shortcut_start_short))
        .setLongLabel(getString(DesignR.string.shortcut_start_long))
        .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
        .setIntent(
            Intent(Intents.ACTION_START_CLASH)
                .setClassName(this, ExternalControlActivity::class.java.name)
                .addFlags(flags)
        )
        .setRank(1)
        .build()

    val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
        .setShortLabel(getString(DesignR.string.shortcut_stop_short))
        .setLongLabel(getString(DesignR.string.shortcut_stop_long))
        .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
        .setIntent(
            Intent(Intents.ACTION_STOP_CLASH)
                .setClassName(this, ExternalControlActivity::class.java.name)
                .addFlags(flags)
        )
        .setRank(2)
        .build()

    ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
}
