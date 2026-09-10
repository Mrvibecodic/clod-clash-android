package com.github.kr328.clash.service.util

import com.github.kr328.clash.service.util.ProfileFields.Violation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProfileFieldsTest {
    private fun violation(
        name: String = "Подписка",
        source: String = "https://panel/sub",
        scheme: String? = "https",
        interval: Long = 0,
        requiresSource: Boolean = true,
    ) = ProfileFields.violation(name, source, scheme, interval, requiresSource)

    @Test
    fun `a sane subscription passes`() {
        assertNull(violation())
        assertNull(violation(interval = TimeUnit.MINUTES.toMillis(15)))
        assertNull(violation(name = "я".repeat(ProfileFields.NAME_MAX)))
        assertNull(violation(source = "https://panel/" + "a".repeat(ProfileFields.SOURCE_MAX - 14)))
    }

    @Test
    fun `an empty name is refused`() {
        assertEquals(Violation.EmptyName, violation(name = ""))
        assertEquals(Violation.EmptyName, violation(name = "   "))
    }

    @Test
    fun `a name past the cap is refused`() {
        assertEquals(Violation.NameTooLong, violation(name = "я".repeat(ProfileFields.NAME_MAX + 1)))
        assertEquals(Violation.NameTooLong, violation(name = "я".repeat(200_000)))
    }

    @Test
    fun `an empty address is refused only where it is required`() {
        assertEquals(Violation.EmptySource, violation(source = "", scheme = null))
        assertNull(violation(source = "", scheme = null, requiresSource = false))
    }

    @Test
    fun `an address past the cap is refused`() {
        val long = "https://panel/" + "a".repeat(ProfileFields.SOURCE_MAX)

        assertEquals(Violation.SourceTooLong, violation(source = long))
        assertEquals(Violation.SourceTooLong, violation(source = long, requiresSource = false))
    }

    @Test
    fun `only https and content addresses pass`() {
        assertEquals(Violation.UnsupportedScheme, violation(source = "http://panel/sub", scheme = "http"))
        assertEquals(Violation.UnsupportedScheme, violation(source = "ftp://panel/sub", scheme = "ftp"))
        assertEquals(Violation.UnsupportedScheme, violation(source = "panel/sub", scheme = null))
        assertNull(violation(source = "content://media/1", scheme = "content"))
    }

    @Test
    fun `an interval shorter than a quarter of an hour is refused`() {
        assertEquals(Violation.ShortInterval, violation(interval = TimeUnit.MINUTES.toMillis(14)))
        assertEquals(Violation.ShortInterval, violation(interval = 1))
        assertNull(violation(interval = 0))
    }

    @Test
    fun `the length caps are checked before the scheme`() {
        assertEquals(
            Violation.NameTooLong,
            violation(name = "я".repeat(ProfileFields.NAME_MAX + 1), source = "http://panel/sub", scheme = "http"),
        )
    }
}
