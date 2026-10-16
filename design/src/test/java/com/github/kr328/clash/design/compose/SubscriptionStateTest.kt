package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.screen.SubscriptionState
import com.github.kr328.clash.design.compose.screen.subscriptionState
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class SubscriptionStateTest {
    private val now = 1_760_000_000_000L

    private val threeDays = TimeUnit.DAYS.toMillis(3)

    private fun profile(expire: Long) = Profile(
        uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        name = "подписка",
        type = Profile.Type.Url,
        source = "https://example.com/sub",
        active = true,
        interval = 0,
        upload = 0,
        download = 0,
        total = 0,
        expire = expire,
        imported = true,
        pending = false,
    )

    @Test
    fun threeDaysLeftIsExpiring() {
        assertEquals(SubscriptionState.Expiring, subscriptionState(profile(now + threeDays), now))
    }

    @Test
    fun activeMeansMoreThanThreeDaysLeft() {
        assertEquals(SubscriptionState.Active, subscriptionState(profile(now + threeDays + 1), now))
    }

    @Test
    fun noExpiryIsActive() {
        assertEquals(SubscriptionState.Active, subscriptionState(profile(0), now))
    }
}
