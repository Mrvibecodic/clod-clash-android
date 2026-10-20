package com.github.kr328.clash.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldStopTest {
    @Test
    fun `стоп ждёт только свой старт, о подъёме которого процесс услышит`() {
        assertTrue(holdStop(sessionActive = false, ownStartPending = true, listening = true))
    }

    @Test
    fun `стоп без своего старта уходит сразу — плитка при поднятом заново процессе`() {
        assertFalse(holdStop(sessionActive = false, ownStartPending = false, listening = true))
        assertFalse(holdStop(sessionActive = false, ownStartPending = false, listening = false))
    }

    @Test
    fun `стоп в процессе, который не слышит рассылок, уходит сразу`() {
        assertFalse(holdStop(sessionActive = false, ownStartPending = true, listening = false))
    }

    @Test
    fun `стоп поднятой сессии уходит сразу`() {
        assertFalse(holdStop(sessionActive = true, ownStartPending = true, listening = true))
        assertFalse(holdStop(sessionActive = true, ownStartPending = false, listening = true))
    }
}
