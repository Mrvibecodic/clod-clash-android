package com.github.kr328.clash.service.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

class GuardedTest {
    @Test
    fun rethrownTypesAreReportedAsIs() {
        val rethrown = listOf<Throwable>(
            SecurityException("s"),
            IllegalArgumentException("a"),
            NullPointerException("n"),
            IllegalStateException("i"),
            UnsupportedOperationException("u"),
        )

        for (e in rethrown) {
            assertEquals(e.toString(), true, rethrownAsIs(e))
        }
    }

    @Test
    fun otherTypesAreNotReportedAsIs() {
        val wrapped = listOf(
            OutOfMemoryError("oom"),
            StackOverflowError("so"),
            RuntimeException("r"),
            Exception("e"),
            Error("err"),
            ArithmeticException("d"),
        )

        for (e in wrapped) {
            assertEquals(e.toString(), false, rethrownAsIs(e))
        }
    }

    @Test
    fun guardedRemoteServicePassesResultThrough() {
        val clash = stub(IClashManager::class.java)
        val profile = stub(IProfileManager::class.java)

        val guarded = GuardedRemoteService(object : IRemoteService {
            override fun clash(): IClashManager = clash
            override fun profile(): IProfileManager = profile
        })

        assertSame(clash, guarded.clash())
        assertSame(profile, guarded.profile())
    }

    private fun <T> stub(type: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null } as T
    }

    @Test
    fun guardedRemoteServiceKeepsTransferableFailure() {
        val guarded = GuardedRemoteService(object : IRemoteService {
            override fun clash(): IClashManager = throw IllegalStateException("not ready")
            override fun profile(): IProfileManager = throw IllegalStateException("not ready")
        })

        try {
            guarded.clash()

            fail("clash() did not fail")
        } catch (e: IllegalStateException) {
            assertEquals("not ready", e.message)
        }

        try {
            guarded.profile()

            fail("profile() did not fail")
        } catch (e: IllegalStateException) {
            assertEquals("not ready", e.message)
        }
    }
}
