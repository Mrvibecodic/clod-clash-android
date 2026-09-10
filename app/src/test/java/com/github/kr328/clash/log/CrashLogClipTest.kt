package com.github.kr328.clash.log

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLogClipTest {
    private fun clip(lines: List<String>, headLines: Int = 4, tailLines: Int = 6) =
        CrashLogClip.clip(lines.asSequence(), headLines, 1_000_000, tailLines, 1_000_000)

    @Test
    fun `a log shorter than the cap keeps every line in the head`() {
        val lines = List(3) { "line $it" }

        val clipped = clip(lines)

        assertEquals(lines, clipped.head)
        assertEquals(0, clipped.dropped)
        assertEquals(emptyList<String>(), clipped.tail)
    }

    @Test
    fun `a log exactly at the cap drops nothing`() {
        val lines = List(10) { "line $it" }

        val clipped = clip(lines)

        assertEquals(lines.take(4), clipped.head)
        assertEquals(0, clipped.dropped)
        assertEquals(lines.drop(4), clipped.tail)
    }

    @Test
    fun `a long log keeps both the first and the last lines`() {
        val lines = List(100) { "line $it" }

        val clipped = clip(lines)

        assertEquals(listOf("line 0", "line 1", "line 2", "line 3"), clipped.head)
        assertEquals(90, clipped.dropped)
        assertEquals(listOf("line 94", "line 95", "line 96", "line 97", "line 98", "line 99"), clipped.tail)
        assertEquals(10, clipped.head.size + clipped.tail.size)
    }

    @Test
    fun `the panic line of a huge log survives in the head`() {
        val lines = listOf("panic: runtime error") + List(100_000) { "goroutine noise $it" }

        val clipped = CrashLogClip.clip(lines.asSequence(), 1250, 131_072, 3750, 393_216)

        assertEquals("panic: runtime error", clipped.head.first())
        assertEquals(1250, clipped.head.size)
        assertEquals("goroutine noise 99999", clipped.tail.last())
    }

    @Test
    fun `an empty stream yields nothing`() {
        val clipped = clip(emptyList())

        assertEquals(emptyList<String>(), clipped.head)
        assertEquals(0, clipped.dropped)
        assertEquals(emptyList<String>(), clipped.tail)
    }

    @Test
    fun `a single line longer than the char cap is kept whole`() {
        val huge = "a".repeat(2_000)

        val clipped = CrashLogClip.clip(sequenceOf(huge), 10, 100, 10, 100)

        assertEquals(listOf(huge), clipped.head)
        assertEquals(0, clipped.dropped)
    }

    @Test
    fun `the char cap moves lines out of the head into the tail`() {
        val lines = List(10) { "0123456789" }

        val clipped = CrashLogClip.clip(lines.asSequence(), 100, 33, 100, 1_000)

        assertEquals(3, clipped.head.size)
        assertEquals(7, clipped.tail.size)
        assertEquals(0, clipped.dropped)
    }

    @Test
    fun `the tail drops by chars as well as by lines`() {
        val lines = List(10) { "0123456789" }

        val clipped = CrashLogClip.clip(lines.asSequence(), 2, 22, 100, 33)

        assertEquals(2, clipped.head.size)
        assertEquals(3, clipped.tail.size)
        assertEquals(5, clipped.dropped)
    }
}
