package com.github.kr328.clash.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class RedirectsTest {
    private val secure = URL("https://host/a/b")
    private val plain = URL("http://host/a/b")

    private fun step(
        from: URL = secure,
        code: Int = 302,
        location: String? = "https://host/c",
        hop: Int = 0,
    ) = Redirects.step(from, code, location, hop)

    @Test
    fun `a plain answer is not a redirect`() {
        for (code in listOf(200, 204, 404, 500)) {
            assertEquals(Redirects.Step.Done, step(code = code))
        }
    }

    @Test
    fun `every redirect code is followed`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            assertEquals(Redirects.Step.Follow(URL("https://host/c")), step(code = code))
        }
    }

    @Test
    fun `a downgrade from https is refused`() {
        val refused = step(location = "http://host/c")

        assertTrue(refused is Redirects.Step.Refuse)
        assertTrue(
            (refused as Redirects.Step.Refuse).reason,
            refused.reason.contains("refused redirect from https"),
        )
    }

    @Test
    fun `a plain source may stay plain`() {
        assertEquals(
            Redirects.Step.Follow(URL("http://host/c")),
            step(from = plain, location = "http://host/c"),
        )
    }

    @Test
    fun `a relative location is resolved against the source`() {
        assertEquals(Redirects.Step.Follow(URL("https://host/a/c")), step(location = "c"))
        assertEquals(Redirects.Step.Follow(URL("https://host/c")), step(location = "/c"))
    }

    @Test
    fun `a relative location cannot drop the scheme`() {
        assertEquals(Redirects.Step.Follow(URL("https://other/c")), step(location = "//other/c"))
    }

    @Test
    fun `a missing location is refused`() {
        assertTrue(step(location = null) is Redirects.Step.Refuse)
        assertTrue(step(location = "") is Redirects.Step.Refuse)
        assertTrue(step(location = " ") is Redirects.Step.Refuse)
    }

    @Test
    fun `an unparsable location is refused`() {
        assertTrue(step(location = "gopher://host/c") is Redirects.Step.Refuse)
    }

    @Test
    fun `the last allowed hop is followed and the next one is refused`() {
        assertEquals(
            Redirects.Step.Follow(URL("https://host/c")),
            step(hop = Redirects.MAX_HOPS - 1),
        )

        assertTrue(step(hop = Redirects.MAX_HOPS) is Redirects.Step.Refuse)
    }

    @Test
    fun `another host is allowed while the scheme holds`() {
        assertEquals(
            Redirects.Step.Follow(URL("https://elsewhere/c")),
            step(location = "https://elsewhere/c"),
        )
    }
}
