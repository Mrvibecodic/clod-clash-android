package com.github.kr328.clash.util

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.github.kr328.clash.service.util.withLocale
import com.github.kr328.clash.service.util.withStoredLocale

// До Android 13 AppCompat загружает свой выбор языка только с первым экраном
// процесса; до этого его пустой список значит «ещё не загружено», а не «как в системе».
@Volatile
internal var appCompatLocalesLoaded = false

fun Context.withAppLocale(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this

    val locale = AppCompatDelegate.getApplicationLocales()[0]
        ?: return if (appCompatLocalesLoaded) this else withStoredLocale()

    return withLocale(locale)
}
