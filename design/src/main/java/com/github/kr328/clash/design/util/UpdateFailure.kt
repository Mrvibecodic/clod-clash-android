package com.github.kr328.clash.design.util

import android.content.Context
import com.github.kr328.clash.design.R

fun Context.humanizeUpdateFailure(raw: String): String? {
    val text = raw.trim()

    if (text.isEmpty()) return null

    if (text.contains(CHAN_STALE)) return getString(R.string.clod_update_cause_clock)

    if (text.contains(CHAN_MISMATCH)) return getString(R.string.clod_update_cause_mismatch)

    if (text.contains(CHAN_BAD_ANSWER)) return getString(R.string.clod_update_cause_bad_answer)

    if (text.contains(NO_SERVERS)) return getString(R.string.clod_update_cause_no_servers)

    if (text.contains(TOO_LARGE)) return getString(R.string.clod_update_cause_too_large)

    if (text.contains(UNSUPPORTED_SCHEME)) return getString(R.string.clod_update_cause_scheme)

    statusOf(text)?.let { status ->
        return when (status) {
            401, 402, 403 -> getString(R.string.clod_update_cause_unauthorized, status)
            404, 410 -> getString(R.string.clod_update_cause_not_found, status)
            in 500..599 -> getString(R.string.clod_update_cause_server, status)
            else -> getString(R.string.clod_update_cause_status, status)
        }
    }

    return null
}

private fun statusOf(text: String): Int? {
    val at = text.indexOf(STATUS_PREFIX)

    if (at < 0) return null

    return text.drop(at + STATUS_PREFIX.length).takeWhile(Char::isDigit).toIntOrNull()
}

private const val CHAN_STALE = "clod-chan-stale"

private const val CHAN_MISMATCH = "clod-chan-mismatch"

private const val CHAN_BAD_ANSWER = "clod-chan-bad-answer"

private const val NO_SERVERS = "does not contain"

private const val TOO_LARGE = "response larger than"

private const val UNSUPPORTED_SCHEME = "unsupported scheme"

private const val STATUS_PREFIX = "server answered with status "
