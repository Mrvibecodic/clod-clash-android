package com.github.kr328.clash.design.compose.component

import com.github.kr328.clash.design.R
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeStateTest {
    @Test
    fun `уведомление без рода считается информацией`() {
        val state = NoticeState()

        state.show("Подписка добавлена", true, detail = "имя")

        assertEquals(NoticeKind.Info, state.current?.kind)
    }

    @Test
    fun `род ошибки доходит до уведомления`() {
        val state = NoticeState()

        state.show("Не удалось обновить", true, detail = "причина", kind = NoticeKind.Error)

        assertEquals(NoticeKind.Error, state.current?.kind)
    }

    @Test
    fun `окно деталей называется ошибкой только для ошибки`() {
        assertEquals(R.string.detail, noticeDetailTitle(NoticeKind.Info))
        assertEquals(R.string.error, noticeDetailTitle(NoticeKind.Error))
    }
}
