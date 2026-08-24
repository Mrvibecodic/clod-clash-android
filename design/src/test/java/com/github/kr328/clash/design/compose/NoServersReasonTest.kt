package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.component.NoServersReason
import com.github.kr328.clash.design.compose.component.noServersReason
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class NoServersReasonTest {
    private val now = 1_760_000_000_000L

    private fun profile(
        expire: Long = 0,
        total: Long = 0,
        upload: Long = 0,
        download: Long = 0,
    ) = Profile(
        uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        name = "подписка",
        type = Profile.Type.Url,
        source = "https://example.org/sub",
        active = true,
        interval = 0,
        upload = upload,
        download = download,
        total = total,
        expire = expire,
        updatedAt = now,
        imported = true,
        pending = false,
    )

    @Test
    fun healthyProfileHasNoReason() {
        val reason = noServersReason(
            profile(expire = now + TimeUnit.DAYS.toMillis(10)),
            PanelInfo(hwidState = "active"),
            now,
        )

        assertNull(reason)
    }

    @Test
    fun deviceLimitWins() {
        val reason = noServersReason(profile(), PanelInfo(hwidState = "limit"), now)

        assertEquals(NoServersReason.DeviceLimit, reason)
    }

    @Test
    fun deviceNotIdentified() {
        val reason = noServersReason(profile(), PanelInfo(hwidState = "not-supported"), now)

        assertEquals(NoServersReason.DeviceNotIdentified, reason)
    }

    @Test
    fun expiredSubscription() {
        val reason = noServersReason(
            profile(expire = now - TimeUnit.DAYS.toMillis(1)),
            PanelInfo(noServers = true),
            now,
        )

        assertEquals(NoServersReason.Expired, reason)
    }

    @Test
    fun exhaustedTraffic() {
        val reason = noServersReason(
            profile(total = 100, upload = 60, download = 40),
            PanelInfo(noServers = true),
            now,
        )

        assertEquals(NoServersReason.Traffic, reason)
    }

    @Test
    fun providerSentNothing() {
        val reason = noServersReason(profile(), PanelInfo(noServers = true), now)

        assertEquals(NoServersReason.Provider, reason)
    }

    @Test
    fun withoutNoServersFlagThereIsNoScreen() {
        val reason = noServersReason(
            profile(expire = now - TimeUnit.DAYS.toMillis(1)),
            PanelInfo(),
            now,
        )

        assertNull(reason)
    }
}
