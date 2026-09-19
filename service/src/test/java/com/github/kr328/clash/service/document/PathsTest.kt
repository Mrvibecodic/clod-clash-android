package com.github.kr328.clash.service.document

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsTest {
    @Test
    fun `a document is a child of its parents only by whole segments`() {
        val uuid = "2c8b8a7e-3d1c-4f0a-9d9e-0f5b6a7c8d9e"

        assertTrue(Paths.isChild("/", "/$uuid/providers/rules.yaml"))
        assertTrue(Paths.isChild("//$uuid", "//$uuid/providers"))
        assertTrue(Paths.isChild("/$uuid/providers", "/$uuid/providers/a/b"))
        assertTrue(Paths.isChild("/$uuid/providers", "/$uuid/providers"))

        assertFalse(Paths.isChild("/$uuid/providers/ab", "/$uuid/providers/abc"))
        assertFalse(Paths.isChild("/$uuid/providers/a", "/$uuid/providers"))
        assertFalse(Paths.isChild("/$uuid/config.yaml", "/$uuid/providers/x"))
    }
}
