package com.github.kr328.clash.service.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.github.kr328.clash.service.PreferenceProvider
import java.util.Locale

const val KEY_APP_LOCALE = "app_locale"

fun Context.withStoredLocale(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this

    val tag = runCatching {
        getSharedPreferences(PreferenceProvider.FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LOCALE, "")
    }.getOrNull().orEmpty()

    if (tag.isEmpty()) return this

    return withLocale(Locale.forLanguageTag(tag))
}

fun Context.withLocale(locale: Locale): Context {
    val configuration = Configuration()

    configuration.setLocale(locale)

    return createConfigurationContext(configuration)
}
