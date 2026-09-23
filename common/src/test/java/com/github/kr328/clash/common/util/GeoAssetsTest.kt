package com.github.kr328.clash.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoAssetsTest {
    private val bundled = GeoAssets.stamp(1000, 500)
    private val imported = GeoAssets.stamp(2000, 900)

    @Test
    fun `a missing or empty file is always extracted`() {
        assertEquals(true, GeoAssets.replaces(present = false, updated = false, recorded = null, actual = bundled))
        assertEquals(true, GeoAssets.replaces(present = false, updated = true, recorded = bundled, actual = bundled))
        assertEquals(true, GeoAssets.replaces(present = false, updated = true, recorded = bundled, actual = imported))
    }

    @Test
    fun `a file that is newer than the apk is kept`() {
        assertEquals(false, GeoAssets.replaces(present = true, updated = false, recorded = null, actual = bundled))
        assertEquals(false, GeoAssets.replaces(present = true, updated = false, recorded = bundled, actual = bundled))
        assertEquals(false, GeoAssets.replaces(present = true, updated = false, recorded = bundled, actual = imported))
    }

    @Test
    fun `an untouched bundled file is replaced after an apk update`() {
        assertEquals(true, GeoAssets.replaces(present = true, updated = true, recorded = bundled, actual = bundled))
    }

    @Test
    fun `a file rewritten since extraction survives an apk update`() {
        assertEquals(false, GeoAssets.replaces(present = true, updated = true, recorded = bundled, actual = imported))
    }

    @Test
    fun `a file without a record is replaced after an apk update`() {
        assertEquals(true, GeoAssets.replaces(present = true, updated = true, recorded = null, actual = imported))
    }

    @Test
    fun `a stamp joins size and modification time`() {
        assertEquals("1000:500", bundled)
    }
}
