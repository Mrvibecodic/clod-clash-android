package com.github.kr328.clash.service.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccessControlFingerprintTest {
    @Test
    fun `accept all ignores the package list`() {
        assertEquals(
            accessControlFingerprint(AccessControlMode.AcceptAll, emptySet()),
            accessControlFingerprint(AccessControlMode.AcceptAll, setOf("a", "b")),
        )
        assertEquals("AcceptAll", accessControlFingerprint(AccessControlMode.AcceptAll, setOf("a")))
    }

    @Test
    fun `selected modes depend on the mode and the sorted list`() {
        assertEquals(
            "AcceptSelected:a,b",
            accessControlFingerprint(AccessControlMode.AcceptSelected, setOf("b", "a")),
        )
        assertEquals(
            "DenySelected:a,b",
            accessControlFingerprint(AccessControlMode.DenySelected, setOf("a", "b")),
        )
        assertNotEquals(
            accessControlFingerprint(AccessControlMode.AcceptSelected, setOf("a")),
            accessControlFingerprint(AccessControlMode.DenySelected, setOf("a")),
        )
        assertNotEquals(
            accessControlFingerprint(AccessControlMode.AcceptSelected, setOf("a")),
            accessControlFingerprint(AccessControlMode.AcceptSelected, setOf("a", "b")),
        )
    }

    @Test
    fun `an empty list in a selected mode differs from accept all`() {
        assertEquals("AcceptSelected:", accessControlFingerprint(AccessControlMode.AcceptSelected, emptySet()))
    }
}
