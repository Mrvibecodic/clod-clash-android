package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.component.TrafficLimit
import com.github.kr328.clash.design.compose.component.trafficLimit
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class TrafficLimitTest {
    private fun profile(
        type: Profile.Type = Profile.Type.Url,
        total: Long = 0,
        quota: Boolean = false,
    ) = Profile(
        uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        name = "подписка",
        type = type,
        source = "https://example.org/sub",
        active = true,
        interval = 0,
        upload = 0,
        download = 0,
        total = total,
        expire = 0,
        updatedAt = 0,
        imported = true,
        pending = false,
        quota = quota,
    )

    @Test
    fun fileWithoutQuotaIsUnknown() {
        assertEquals(TrafficLimit.Unknown, profile(type = Profile.Type.File).trafficLimit())
    }

    @Test
    fun urlWithoutHeaderIsUnknown() {
        assertEquals(TrafficLimit.Unknown, profile().trafficLimit())
    }

    @Test
    fun zeroTotalFromPanelIsUnlimited() {
        assertEquals(TrafficLimit.Unlimited, profile(quota = true).trafficLimit())
    }

    @Test
    fun positiveTotalIsLimited() {
        assertEquals(TrafficLimit.Limited(100), profile(total = 100, quota = true).trafficLimit())
    }
}
