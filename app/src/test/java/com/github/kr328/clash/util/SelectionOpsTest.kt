package com.github.kr328.clash.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionOpsTest {
    private val hidden = setOf("sys.hidden")
    private val selected = setOf("a", "sys.hidden")
    private val visible = setOf("a", "b", "c")

    @Test
    fun `select all adds the visible apps and keeps the hidden ones`() {
        assertEquals(
            setOf("a", "b", "c") + hidden,
            SelectionOps.apply(SelectionOps.Op.SelectAll, selected, visible),
        )
    }

    @Test
    fun `select none removes only the visible apps`() {
        assertEquals(hidden, SelectionOps.apply(SelectionOps.Op.SelectNone, selected, visible))
    }

    @Test
    fun `invert flips only the visible apps`() {
        assertEquals(
            setOf("b", "c") + hidden,
            SelectionOps.apply(SelectionOps.Op.Invert, selected, visible),
        )
    }
}
