package com.github.kr328.clash.design.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import com.github.kr328.clash.design.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun relativeTime(millis: Long, now: Long): String {
    if (millis <= 0) return stringResource(R.string.clod_never)

    val elapsed = now - millis
    val days = TimeUnit.MILLISECONDS.toDays(elapsed).toInt()
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed).toInt()
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed).toInt()

    return when {
        days > 0 -> pluralStringResource(R.plurals.clod_days_ago, days, days)
        hours > 0 -> pluralStringResource(R.plurals.clod_hours_ago, hours, hours)
        minutes > 0 -> pluralStringResource(R.plurals.clod_minutes_ago, minutes, minutes)
        else -> stringResource(R.string.clod_just_now)
    }
}

@Composable
fun formatDate(millis: Long): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()

    return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))
}
