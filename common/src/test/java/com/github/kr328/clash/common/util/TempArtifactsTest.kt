package com.github.kr328.clash.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TempArtifactsTest {
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    private fun deletable(
        name: String,
        ownPid: Int = 1000,
        writerAlive: Boolean? = null,
        lastModified: Long = now,
    ) = TempArtifacts.deletable(name, ownPid, writerAlive, lastModified, now, hour)

    @Test
    fun `the writer pid is read from the name`() {
        assertEquals(2345, TempArtifacts.pidOf("geoip.metadb.extracting.2345"))
        assertNull(TempArtifacts.pidOf("geoip.metadb.extracting"))
        assertNull(TempArtifacts.pidOf("geoip.metadb.extracting.abc"))
        assertNull(TempArtifacts.pidOf("geoip.metadb.extracting.0"))
        assertNull(TempArtifacts.pidOf("geoip.metadb"))
    }

    @Test
    fun `a file that is not a temp artifact is never deleted`() {
        assertFalse(deletable("geoip.metadb", lastModified = 0))
        assertFalse(deletable("geosite.dat", lastModified = 0))
    }

    @Test
    fun `own leftover goes away regardless of the clock`() {
        assertTrue(deletable("geoip.metadb.extracting.1000", ownPid = 1000, lastModified = now))
        assertTrue(deletable("geoip.metadb.extracting.1000", ownPid = 1000, lastModified = now + hour * 10))
    }

    @Test
    fun `a file of a live process is kept even when the clock jumped forward`() {
        assertFalse(
            deletable(
                "geoip.metadb.extracting.2345",
                writerAlive = true,
                lastModified = now - hour * 10,
            )
        )
    }

    @Test
    fun `a file of a dead process goes away at once`() {
        assertTrue(
            deletable(
                "geoip.metadb.extracting.2345",
                writerAlive = false,
                lastModified = now,
            )
        )
    }

    @Test
    fun `a name without a pid falls back to the age rule`() {
        assertFalse(deletable("geoip.metadb.extracting", lastModified = now))
        assertFalse(deletable("geoip.metadb.extracting", lastModified = now - hour))
        assertTrue(deletable("geoip.metadb.extracting", lastModified = now - hour - 1))
    }

    @Test
    fun `a name with a broken tail falls back to the age rule`() {
        assertFalse(deletable("geoip.metadb.extracting.abc", lastModified = now))
        assertTrue(deletable("geoip.metadb.extracting.abc", lastModified = now - hour * 2))
    }

    @Test
    fun `an unknown liveness falls back to the age rule`() {
        assertFalse(deletable("geoip.metadb.extracting.2345", writerAlive = null, lastModified = now))
        assertTrue(
            deletable("geoip.metadb.extracting.2345", writerAlive = null, lastModified = now - hour * 2)
        )
    }

    @Test
    fun `a clock that went backwards never deletes a file of a live process`() {
        assertFalse(
            deletable(
                "geoip.metadb.extracting.2345",
                writerAlive = true,
                lastModified = now + hour * 24,
            )
        )
        assertFalse(
            deletable(
                "geoip.metadb.extracting",
                lastModified = now + hour * 24,
            )
        )
    }
}
