package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.service.R

object UpdateFailures {
    enum class Cause {
        Downgrade,
        Clock,
        Mismatch,
        BadAnswer,
        NoServers,
        TooLarge,
        Scheme,
        Unauthorized,
        NotFound,
        ServerError,
        Status,
    }

    data class Reason(val cause: Cause, val status: Int = 0)

    fun classify(raw: String): Reason? {
        val text = raw.trim()

        if (text.isEmpty()) return null

        if (text.contains(REDIRECT_DOWNGRADE)) return Reason(Cause.Downgrade)

        if (text.contains(CHAN_STALE)) return Reason(Cause.Clock)

        if (text.contains(CHAN_MISMATCH)) return Reason(Cause.Mismatch)

        if (text.contains(CHAN_BAD_ANSWER)) return Reason(Cause.BadAnswer)

        if (text.contains(NO_SERVERS)) return Reason(Cause.NoServers)

        if (text.contains(TOO_LARGE)) return Reason(Cause.TooLarge)

        if (text.contains(UNSUPPORTED_SCHEME)) return Reason(Cause.Scheme)

        val status = statusOf(text) ?: return null

        return when (status) {
            401, 402, 403 -> Reason(Cause.Unauthorized, status)
            404, 410 -> Reason(Cause.NotFound, status)
            in 500..599 -> Reason(Cause.ServerError, status)
            else -> Reason(Cause.Status, status)
        }
    }

    private fun statusOf(text: String): Int? {
        val at = text.indexOf(STATUS_PREFIX)

        if (at < 0) return null

        return text.drop(at + STATUS_PREFIX.length).takeWhile(Char::isDigit).toIntOrNull()
    }

    private const val REDIRECT_DOWNGRADE = "refused redirect from https"

    private const val CHAN_STALE = "clod-chan-stale"

    private const val CHAN_MISMATCH = "clod-chan-mismatch"

    private const val CHAN_BAD_ANSWER = "clod-chan-bad-answer"

    private const val NO_SERVERS = "does not contain"

    private const val TOO_LARGE = "response larger than"

    private const val UNSUPPORTED_SCHEME = "unsupported scheme"

    private const val STATUS_PREFIX = "server answered with status "
}

fun Context.humanizeUpdateFailure(raw: String): String? {
    val reason = UpdateFailures.classify(raw) ?: return null

    return when (reason.cause) {
        UpdateFailures.Cause.Downgrade -> getString(R.string.clod_update_cause_downgrade)
        UpdateFailures.Cause.Clock -> getString(R.string.clod_update_cause_clock)
        UpdateFailures.Cause.Mismatch -> getString(R.string.clod_update_cause_mismatch)
        UpdateFailures.Cause.BadAnswer -> getString(R.string.clod_update_cause_bad_answer)
        UpdateFailures.Cause.NoServers -> getString(R.string.clod_update_cause_no_servers)
        UpdateFailures.Cause.TooLarge -> getString(R.string.clod_update_cause_too_large)
        UpdateFailures.Cause.Scheme -> getString(R.string.clod_update_cause_scheme)
        UpdateFailures.Cause.Unauthorized ->
            getString(R.string.clod_update_cause_unauthorized, reason.status)
        UpdateFailures.Cause.NotFound ->
            getString(R.string.clod_update_cause_not_found, reason.status)
        UpdateFailures.Cause.ServerError ->
            getString(R.string.clod_update_cause_server, reason.status)
        UpdateFailures.Cause.Status ->
            getString(R.string.clod_update_cause_status, reason.status)
    }
}
