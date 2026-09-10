package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.util.UpdateFailures.Cause
import com.github.kr328.clash.service.util.UpdateFailures.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateFailuresTest {
    @Test
    fun `an empty reason is not explained`() {
        assertNull(UpdateFailures.classify(""))
        assertNull(UpdateFailures.classify("   "))
    }

    @Test
    fun `a reason without any known mark is not explained`() {
        assertNull(UpdateFailures.classify("dial tcp 10.0.0.1:443: i/o timeout"))
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
}
