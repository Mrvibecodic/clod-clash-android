package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.util.Redact
import com.github.kr328.clash.service.R

object UpdateFailures {
    enum class Cause {
        Downgrade,
        Clock,
        Mismatch,
        BadAnswer,
        NotDelivered,
        NoServers,
        TooLarge,
        Scheme,
        Unauthorized,
        NotFound,
        ServerError,
        Status,
        Rejected,
        DraftChanged,
        AlreadyAdded,
        Storage,
        Timeout,
        Tls,
        Dns,
        Connection,
        Silent,
        ;

        // Причина в сети по дороге, а не в ответе сервера
        val transport: Boolean
            get() = this == Timeout || this == Tls || this == Dns || this == Connection
    }

    // Что сказал защищённый канал при добавлении подписки или его включении.
    enum class Channel {
        // Сервер на месте, а канала у него нет: можно загружать обычным путём.
        Absent,
        // Отказал посредник по дороге (не 404), сервер закрыл соединение, не
        // ответив, или ответил 5xx (так отвечает прослойка с выключенным каналом
        // старых версий и Cloudflare перед nginx с 444): канал мог и быть,
        // пробовать снова; так все попытки — канала нет.
        Doubtful,
        // Канал не ответил (сеть, таймаут): пробовать его снова.
        Silent,
        // Канал ответил — отказом панели, сбитыми часами, чужим ответом: это и показать.
        Answered,
    }

    // adding — добавление подписки: 5xx там — «канал мог быть», и после всех
    // попыток подписка добавляется обычным путём. При включении канала у
    // добавленной 5xx — сбой сервера, а не «канала нет»
    fun channel(raw: String, adding: Boolean = true): Channel = when {
        raw.contains(CHAN_FAILED) && !adding -> Channel.Silent
        raw.contains(CHAN_DROPPED) || raw.contains(CHAN_FAILED) -> Channel.Doubtful
        raw.contains(CHAN_SILENT) -> Channel.Silent
        raw.contains(CHAN_BAD_ANSWER) || raw.contains(CHAN_BAD_URL) -> Channel.Absent
        raw.contains(CHAN_DOUBT) -> Channel.Doubtful
        else -> Channel.Answered
    }

    // Итог неудачной попытки канала из attempts; null — пробовать ещё раз.
    // alwaysDoubted — все прежние попытки кончились отказом посредника или
    // обрывом: если и эта так же, канала нет (обычный путь); хоть одно
    // молчание сети — ошибка, иначе случайный сбой лишал бы подписку канала.
    fun channelVerdict(channel: Channel, attempt: Int, attempts: Int, alwaysDoubted: Boolean): Channel? = when {
        channel == Channel.Absent || channel == Channel.Answered -> channel
        attempt < attempts -> null
        alwaysDoubted && channel == Channel.Doubtful -> Channel.Absent
        else -> Channel.Silent
    }

    data class Reason(val cause: Cause, val status: Int = 0)

    fun classify(raw: String): Reason? {
        val text = raw.trim()

        if (text.isEmpty()) return null

        if (text.contains(REDIRECT_DOWNGRADE)) return Reason(Cause.Downgrade)

        if (text.contains(CHAN_STALE)) return Reason(Cause.Clock)

        if (text.contains(CHAN_MISMATCH)) return Reason(Cause.Mismatch)

        if (text.contains(CHAN_BAD_ANSWER) || text.contains(CHAN_DOUBT) || text.contains(CHAN_MALFORMED)) {
            return Reason(Cause.BadAnswer)
        }

        if (text.contains(NOT_DELIVERED)) return Reason(Cause.NotDelivered)

        if (text.contains(NO_SERVERS)) return Reason(Cause.NoServers)

        if (text.contains(TOO_LARGE)) return Reason(Cause.TooLarge)

        if (text.contains(UNSUPPORTED_SCHEME)) return Reason(Cause.Scheme)

        if (text.contains(CONFIG_REJECTED)) return Reason(Cause.Rejected)

        if (text.contains(DRAFT_CHANGED)) return Reason(Cause.DraftChanged)

        if (text.contains(ALREADY_ADDED)) return Reason(Cause.AlreadyAdded)

        if (noSpace(text)) return Reason(Cause.Storage)

        val status = statusOf(text)
            ?: return transportOf(text.lowercase())
                ?: Reason(Cause.Silent).takeIf { text.contains(CHAN_SILENT) }

        return when (status) {
            401, 402, 403 -> Reason(Cause.Unauthorized, status)
            404, 410 -> Reason(Cause.NotFound, status)
            in 500..599 -> Reason(Cause.ServerError, status)
            else -> Reason(Cause.Status, status)
        }
    }

    fun noSpace(raw: String): Boolean = raw.lowercase().let { text -> STORAGE.any(text::contains) }

    // Причины, у которых общая фраза без подробности бесполезна: строка YAML или
    // сертификата нужна человеку, чтобы понять, что именно не так.
    fun detail(raw: String): String? {
        if (classify(raw)?.cause !in DETAILED) return null

        return Redact.text(raw.replace(CONFIG_REJECTED_MARK, "").trim()).takeIf { it.isNotEmpty() }
    }

    private fun transportOf(text: String): Reason? = when {
        TIMEOUT.any(text::contains) -> Reason(Cause.Timeout)
        TLS.any(text::contains) -> Reason(Cause.Tls)
        DNS.any(text::contains) -> Reason(Cause.Dns)
        CONNECTION.any(text::contains) || text.endsWith(": eof") -> Reason(Cause.Connection)
        else -> null
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

    // Ядро (chanx): код ответа поставил посредник по дороге, а не прослойка.
    private const val CHAN_DOUBT = "clod-chan-doubt"

    // Ядро (chanx): ответ канала расшифровался, но не разобрался.
    private const val CHAN_MALFORMED = "clod-chan-malformed"

    // Ядро (chanx): адрес без пути с меткой — канала по нему не бывает.
    private const val CHAN_BAD_URL = "clod-chan-bad-url"

    // Ядро (chanx): сервер закрыл соединение, не ответив.
    private const val CHAN_DROPPED = "clod-chan-dropped"

    // Ядро (chanx): по адресу канала ответили 5xx.
    private const val CHAN_FAILED = "clod-chan-failed"

    // Ядро (chanx): канал не ответил, под меткой — сетевая причина.
    private const val CHAN_SILENT = "clod-chan-silent"

    // Служба: адрес правки уже у другой подписки.
    const val ALREADY_ADDED = "clod-already-added"

    private const val NOT_DELIVERED = "clod-not-delivered"

    private const val NO_SERVERS = "does not contain"

    private const val TOO_LARGE = "response larger than"

    private const val UNSUPPORTED_SCHEME = "unsupported scheme"

    private const val STATUS_PREFIX = "server answered with status "

    private const val CONFIG_REJECTED = "clod-config-rejected"

    private const val CONFIG_REJECTED_MARK = "$CONFIG_REJECTED: "

    // Отказ службы доходит до экрана только текстом (kaidl не сохраняет тип
    // исключения), поэтому причина передаётся меткой, а фразу выбирает экран.
    const val DRAFT_CHANGED = "clod-draft-changed"

    private val DETAILED = setOf(Cause.Rejected, Cause.Tls)

    private val STORAGE = listOf("no space left on device", "enospc", "disk is full")

    private val TIMEOUT = listOf(
        "time budget for the update is exhausted",
        "context deadline exceeded",
        "i/o timeout",
        "tls handshake timeout",
        "timeout awaiting response headers",
        "client.timeout exceeded",
    )

    private val TLS = listOf("x509:", "certificate")

    private val DNS = listOf("no such host", "couldn't find ip", "all dns requests failed")

    private val CONNECTION = listOf(
        "connection refused",
        "network is unreachable",
        "no route to host",
        "connection reset",
        "broken pipe",
        "unexpected eof",
        "tls:",
    )
}

fun Context.humanizeUpdateFailure(raw: String): String? {
    val reason = UpdateFailures.classify(raw) ?: return null

    return when (reason.cause) {
        UpdateFailures.Cause.Downgrade -> getString(R.string.clod_update_cause_downgrade)
        UpdateFailures.Cause.Clock -> getString(R.string.clod_update_cause_clock)
        UpdateFailures.Cause.Mismatch -> getString(R.string.clod_update_cause_mismatch)
        UpdateFailures.Cause.BadAnswer -> getString(R.string.clod_update_cause_bad_answer)
        UpdateFailures.Cause.NotDelivered -> getString(R.string.clod_update_cause_not_delivered)
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
        UpdateFailures.Cause.Rejected -> getString(R.string.clod_update_cause_rejected)
        UpdateFailures.Cause.DraftChanged -> getString(R.string.clod_draft_changed)
        UpdateFailures.Cause.AlreadyAdded -> getString(R.string.clod_update_cause_already_added)
        UpdateFailures.Cause.Storage -> getString(R.string.clod_update_cause_storage)
        UpdateFailures.Cause.Timeout -> getString(R.string.clod_update_cause_timeout)
        UpdateFailures.Cause.Tls -> getString(R.string.clod_update_cause_tls)
        UpdateFailures.Cause.Dns -> getString(R.string.clod_update_cause_dns)
        UpdateFailures.Cause.Connection -> getString(R.string.clod_update_cause_connection)
        UpdateFailures.Cause.Silent -> getString(R.string.clod_update_cause_silent)
    }
}
