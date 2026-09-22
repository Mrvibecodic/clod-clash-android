package com.github.kr328.clash.design.compose

import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SubscriptionItemTest {
    private fun item(type: Profile.Type, imported: Boolean) = SubscriptionItem(
        Profile(
            uuid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            name = "подписка",
            type = type,
            source = "https://example.org/sub",
            active = true,
            interval = 0,
            upload = 0,
            download = 0,
            total = 0,
            expire = 0,
            updatedAt = 0,
            imported = imported,
            pending = false,
        ),
    )

    @Test
    fun loadedUrlSubscriptionIsUpdatable() {
        assertTrue(item(Profile.Type.Url, imported = true).updatable)
    }

    @Test
    fun fileSubscriptionIsNotUpdatable() {
        assertFalse(item(Profile.Type.File, imported = true).updatable)
    }

    @Test
    fun draftIsNotUpdatable() {
        assertFalse(item(Profile.Type.Url, imported = false).updatable)
    }
}
