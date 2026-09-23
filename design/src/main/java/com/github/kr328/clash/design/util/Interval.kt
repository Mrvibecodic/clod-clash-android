package com.github.kr328.clash.design.util

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.github.kr328.clash.design.R
import java.util.Date
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
fun formatDate(millis: Long): String = DateFormat.getMediumDateFormat(LocalContext.current).format(Date(millis))
