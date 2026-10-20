package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.util.UpdateFailures.Cause
import com.github.kr328.clash.service.util.UpdateFailures.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFailuresTest {
    @Test
    fun `an empty reason is not explained`() {
        assertNull(UpdateFailures.classify(""))
        assertNull(UpdateFailures.classify("   "))
    }

    @Test
    fun `a reason without any known mark is not explained`() {
        assertNull(UpdateFailures.classify("something nobody has seen before"))
    }

    @Test
    fun `a downgraded redirect is explained`() {
        assertEquals(
            Reason(Cause.Downgrade),
            UpdateFailures.classify("Get \"https://p/s\": refused redirect from https to http"),
        )
    }

    @Test
    fun `the secure channel marks are explained`() {
        assertEquals(Reason(Cause.Clock), UpdateFailures.classify("clod-chan-stale"))
        assertEquals(Reason(Cause.Mismatch), UpdateFailures.classify("clod-chan-mismatch"))
        assertEquals(Reason(Cause.BadAnswer), UpdateFailures.classify("clod-chan-bad-answer"))
    }

    @Test
    fun `a silent channel is explained by the cause under the mark`() {
        assertEquals(
            Reason(Cause.Timeout),
            UpdateFailures.classify("clod-chan-silent: Get \"https://p/c1/x\": context deadline exceeded"),
        )
        assertEquals(
            Reason(Cause.ServerError, 502),
            UpdateFailures.classify("clod-chan-silent: server answered with status 502"),
        )
        assertEquals(Reason(Cause.Silent), UpdateFailures.classify("clod-chan-silent: something odd"))
    }

    // Исходы ядра (chanx) при добавлении подписки, как их пишет Silence: без
    // прослойки и с выключенным каналом — обычный путь после всех попыток,
    // без ответа — ошибка
    @Test
    fun `adding decides by what the core said`() {
        val absent = "clod-chan-bad-answer: relay answered with status 404"
        val dropped = "clod-chan-silent: clod-chan-dropped: Get \"https://p/c1/x\": EOF"
        val failed = "clod-chan-silent: clod-chan-failed: server answered with status 502"
        val doubt = "clod-chan-doubt: relay answered with status 403"
        val silent = "clod-chan-silent: dial tcp: i/o timeout"

        assertEquals(UpdateFailures.Channel.Absent, UpdateFailures.channel(absent))

        for (raw in listOf(dropped, failed, doubt)) {
            val channel = UpdateFailures.channel(raw)

            assertEquals(raw, UpdateFailures.Channel.Doubtful, channel)
            assertEquals(raw, null, UpdateFailures.channelVerdict(channel, 1, 3, alwaysDoubted = true))
            assertEquals(raw, UpdateFailures.Channel.Absent, UpdateFailures.channelVerdict(channel, 3, 3, alwaysDoubted = true))
        }

        assertEquals(Reason(Cause.ServerError, 502), UpdateFailures.classify(failed))
        // Включение канала у добавленной подписки: 5xx — сбой сервера, не «канала нет»
        assertEquals(UpdateFailures.Channel.Silent, UpdateFailures.channel(failed, adding = false))
        assertEquals(UpdateFailures.Channel.Doubtful, UpdateFailures.channel(dropped, adding = false))
        assertEquals(UpdateFailures.Channel.Silent, UpdateFailures.channel(silent))
        assertEquals(
            UpdateFailures.Channel.Silent,
            UpdateFailures.channelVerdict(UpdateFailures.Channel.Doubtful, 3, 3, alwaysDoubted = false),
        )
    }

    @Test
    fun `an edit onto another subscription address is told plainly`() {
        assertEquals(
            Reason(Cause.AlreadyAdded),
            UpdateFailures.classify("java.lang.IllegalArgumentException: ${UpdateFailures.ALREADY_ADDED}"),
        )
    }

    @Test
    fun `the channel is told absent, silent or answered`() {
        assertEquals(
            UpdateFailures.Channel.Absent,
            UpdateFailures.channel("clod-chan-bad-answer: relay answered with status 404"),
        )
        assertEquals(UpdateFailures.Channel.Absent, UpdateFailures.channel("clod-chan-bad-answer"))
        // Адрес без пути с меткой: канала нет, подписка идёт обычным путём.
        assertEquals(
            UpdateFailures.Channel.Absent,
            UpdateFailures.channel("clod-chan-bad-url: address without token: https://p/?token=x"),
        )
        assertEquals(
            UpdateFailures.Channel.Silent,
            UpdateFailures.channel("clod-chan-silent: dial tcp: i/o timeout"),
        )
        assertEquals(
            UpdateFailures.Channel.Silent,
            UpdateFailures.channel("clod-chan-silent: time budget for the update is exhausted"),
        )
        assertEquals(UpdateFailures.Channel.Answered, UpdateFailures.channel("clod-chan-stale"))
        assertEquals(UpdateFailures.Channel.Answered, UpdateFailures.channel("server answered with status 404"))
        assertEquals(UpdateFailures.Channel.Answered, UpdateFailures.channel("clod-config-rejected: yaml: line 3"))
    }

    @Test
    fun `a middlebox refusal is doubtful and a garbled answer is answered`() {
        assertEquals(
            UpdateFailures.Channel.Doubtful,
            UpdateFailures.channel("clod-chan-doubt: relay answered with status 403"),
        )
        assertEquals(UpdateFailures.Channel.Answered, UpdateFailures.channel("clod-chan-malformed"))
        assertEquals(
            UpdateFailures.Channel.Answered,
            UpdateFailures.channel("response larger than 33554432 bytes"),
        )
        // Тексты для обновления — прежние: «ответили не тем, что понимает канал».
        assertEquals(
            Reason(Cause.BadAnswer),
            UpdateFailures.classify("clod-chan-doubt: relay answered with status 403"),
        )
        assertEquals(Reason(Cause.BadAnswer), UpdateFailures.classify("clod-chan-malformed"))
    }

    @Test
    fun `only a failure on the way is a network cause, not an answer the server gave`() {
        assertEquals(true, UpdateFailures.classify("clod-chan-silent: dial tcp: i/o timeout")?.cause?.transport)
        assertEquals(true, UpdateFailures.classify("clod-chan-silent: Get \"https://sub.example.com/***\": EOF")?.cause?.transport)
        assertEquals(false, UpdateFailures.classify("clod-chan-doubt: relay answered with status 403")?.cause?.transport)
        assertEquals(false, UpdateFailures.classify("server answered with status 502")?.cause?.transport)
    }

    @Test
    fun `a connection the server closed unanswered is doubtful but keeps the connection text`() {
        val raw = "clod-chan-silent: clod-chan-dropped: Get \"https://sub.example.com/***\": EOF"

        assertEquals(UpdateFailures.Channel.Doubtful, UpdateFailures.channel(raw))
        assertEquals(Reason(Cause.Connection), UpdateFailures.classify(raw))
    }

    @Test
    fun `the channel attempts end as absent only when every attempt was refused`() {
        val absent = UpdateFailures.Channel.Absent
        val doubtful = UpdateFailures.Channel.Doubtful
        val silent = UpdateFailures.Channel.Silent
        val answered = UpdateFailures.Channel.Answered

        fun run(vararg outcomes: UpdateFailures.Channel): UpdateFailures.Channel? {
            var alwaysDoubted = true
            outcomes.forEachIndexed { i, outcome ->
                UpdateFailures.channelVerdict(outcome, i + 1, outcomes.size, alwaysDoubted)?.let { return it }
                alwaysDoubted = alwaysDoubted && outcome == doubtful
            }
            return null
        }

        // Нет канала (404 или не c1) — сразу, без повторов.
        assertEquals(absent, UpdateFailures.channelVerdict(absent, 1, 3, false))
        assertEquals(answered, UpdateFailures.channelVerdict(answered, 1, 3, false))
        // Отказ посредника и молчание до последней попытки — ещё раз.
        assertNull(UpdateFailures.channelVerdict(doubtful, 1, 3, false))
        assertNull(UpdateFailures.channelVerdict(silent, 2, 3, true))

        assertEquals(absent, run(doubtful, doubtful, doubtful))
        // Хоть одно молчание сети — ошибка: случайный сбой не лишает канала
        assertEquals(silent, run(doubtful, silent, silent))
        assertEquals(silent, run(silent, silent, doubtful))
        assertEquals(silent, run(doubtful, silent, doubtful))
        assertEquals(silent, run(silent, silent, silent))
        assertEquals(absent, run(silent, absent))
    }

    @Test
    fun `an answer without a subscription is explained`() {
        assertEquals(
            Reason(Cause.NotDelivered),
            UpdateFailures.classify(
                "clod-not-delivered: the address answered with an empty answer or a web page instead of the subscription",
            ),
        )
    }

    @Test
    fun `a draft changed during loading is explained`() {
        assertEquals(
            Reason(Cause.DraftChanged),
            UpdateFailures.classify("java.lang.IllegalStateException: ${UpdateFailures.DRAFT_CHANGED}"),
        )
        assertNull(UpdateFailures.detail(UpdateFailures.DRAFT_CHANGED))
    }

    @Test
    fun `an empty subscription is explained`() {
        assertEquals(
            Reason(Cause.NoServers),
            UpdateFailures.classify("configuration does not contain any proxy"),
        )
    }

    @Test
    fun `an oversized answer is explained`() {
        assertEquals(
            Reason(Cause.TooLarge),
            UpdateFailures.classify("response larger than 10485760 bytes"),
        )
    }

    @Test
    fun `an unsupported scheme is explained`() {
        assertEquals(Reason(Cause.Scheme), UpdateFailures.classify("unsupported scheme ftp"))
    }

    @Test
    fun `refusal codes are explained by their group`() {
        for (status in listOf(401, 402, 403)) {
            assertEquals(
                Reason(Cause.Unauthorized, status),
                UpdateFailures.classify("server answered with status $status"),
            )
        }
    }

    @Test
    fun `missing subscription codes are explained by their group`() {
        for (status in listOf(404, 410)) {
            assertEquals(
                Reason(Cause.NotFound, status),
                UpdateFailures.classify("server answered with status $status"),
            )
        }
    }

    @Test
    fun `server side codes are explained by their group`() {
        for (status in listOf(500, 502, 599)) {
            assertEquals(
                Reason(Cause.ServerError, status),
                UpdateFailures.classify("server answered with status $status"),
            )
        }
    }

    @Test
    fun `any other code is reported as a bare code`() {
        assertEquals(
            Reason(Cause.Status, 418),
            UpdateFailures.classify("server answered with status 418"),
        )
        assertEquals(
            Reason(Cause.Status, 600),
            UpdateFailures.classify("server answered with status 600"),
        )
    }

    @Test
    fun `a status that is not a number is not explained`() {
        assertNull(UpdateFailures.classify("server answered with status abc"))
    }

    @Test
    fun `a mark wins over a status in the same text`() {
        assertEquals(
            Reason(Cause.Clock),
            UpdateFailures.classify("clod-chan-stale: server answered with status 403"),
        )
    }

    @Test
    fun `a config the core rejected is explained apart from a failed download`() {
        assertEquals(
            Reason(Cause.Rejected),
            UpdateFailures.classify("clod-config-rejected: yaml: line 3: mapping values are not allowed"),
        )
    }

    @Test
    fun `an empty subscription wins over the rejected mark`() {
        assertEquals(
            Reason(Cause.NoServers),
            UpdateFailures.classify("clod-config-rejected: profile does not contain `proxies` or `proxy-providers`"),
        )
    }

    @Test
    fun `timeouts are explained`() {
        for (raw in listOf(
            "Get \"https://p/s\": context deadline exceeded",
            "dial tcp 10.0.0.1:443: i/o timeout",
            "Get \"https://p/s\": net/http: TLS handshake timeout",
            "time budget for the update is exhausted",
        )) {
            assertEquals(raw, Reason(Cause.Timeout), UpdateFailures.classify(raw))
        }
    }

    @Test
    fun `certificate failures are explained`() {
        for (raw in listOf(
            "Get \"https://p/s\": tls: failed to verify certificate: x509: certificate has expired",
            "Get \"https://p/s\": x509: certificate signed by unknown authority",
        )) {
            assertEquals(raw, Reason(Cause.Tls), UpdateFailures.classify(raw))
        }
    }

    @Test
    fun `name resolution failures are explained`() {
        for (raw in listOf(
            "Get \"https://p/s\": dial tcp: lookup p on 8.8.8.8:53: no such host",
            "couldn't find ip: p",
            "all DNS requests failed",
        )) {
            assertEquals(raw, Reason(Cause.Dns), UpdateFailures.classify(raw))
        }
    }

    @Test
    fun `connection failures are explained`() {
        for (raw in listOf(
            "Get \"https://p/s\": dial tcp 10.0.0.1:443: connect: connection refused",
            "Get \"https://p/s\": dial tcp 10.0.0.1:443: connect: network is unreachable",
            "Get \"https://p/s\": read tcp 10.0.0.2:5000->10.0.0.1:443: read: connection reset by peer",
            "Get \"https://p/s\": EOF",
            "Get \"https://p/s\": remote error: tls: handshake failure",
        )) {
            assertEquals(raw, Reason(Cause.Connection), UpdateFailures.classify(raw))
        }
    }

    @Test
    fun `a status code still wins over transport words`() {
        assertEquals(
            Reason(Cause.ServerError, 502),
            UpdateFailures.classify("server answered with status 502 after connection reset"),
        )
    }

    @Test
    fun `a rejected config keeps its detail without the mark`() {
        assertEquals(
            "yaml: line 3: mapping values are not allowed",
            UpdateFailures.detail("clod-config-rejected: yaml: line 3: mapping values are not allowed"),
        )
    }

    @Test
    fun `a certificate failure keeps its detail`() {
        assertEquals(
            "x509: certificate signed by unknown authority",
            UpdateFailures.detail("x509: certificate signed by unknown authority"),
        )
    }

    @Test
    fun `other explained failures carry no detail`() {
        assertNull(UpdateFailures.detail("server answered with status 404"))
        assertNull(UpdateFailures.detail("Get \"https://p/s\": dial tcp: i/o timeout"))
        assertNull(UpdateFailures.detail("something nobody has seen before"))
        assertNull(UpdateFailures.detail("clod-config-rejected: "))
    }

    @Test
    fun `a full storage is explained and is not a network failure`() {
        listOf(
            "write failed: ENOSPC (No space left on device)",
            "write /data/user/0/example/files/processing/config.yaml: no space left on device",
            "database or disk is full (code 13 SQLITE_FULL)",
        ).forEach {
            assertEquals(Reason(Cause.Storage), UpdateFailures.classify(it))
            assertFalse(Cause.Storage.transport)
            assertTrue(UpdateFailures.noSpace(it))
        }

        assertFalse(UpdateFailures.noSpace("Get \"https://example.com/s\": dial tcp: i/o timeout"))
    }
}
