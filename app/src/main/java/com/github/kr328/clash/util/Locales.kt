package com.github.kr328.clash.util

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.github.kr328.clash.service.util.withLocale

fun Context.withAppLocale(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this

    val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return this

    return withLocale(locale)
}
