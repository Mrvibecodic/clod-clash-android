package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class AppStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var updatedAt: Long by store.long(
        key = "updated_at",
        defaultValue = -1,
    )

    var autoCheckUpdate: Boolean by store.boolean(
        key = "auto_check_update",
        defaultValue = true,
    )

    var prereleaseChannel: Boolean by store.boolean(
        key = "nightly_channel",
        defaultValue = false,
    )

    var lastUpdateCheck: Long by store.long(
        key = "last_update_check",
        defaultValue = 0,
    )

    var lastUpdateCheckFailed: Boolean by store.boolean(
        key = "last_update_check_failed",
        defaultValue = false,
    )

    var skippedVersionCode: Long by store.long(
        key = "skipped_version_code",
        defaultValue = 0,
    )

    var addedProfilePending: Boolean by store.boolean(
        key = "added_profile_pending",
        defaultValue = false,
    )

    var addedProfileName: String by store.string(
        key = "added_profile_name",
        defaultValue = "",
    )

    companion object {
        private const val FILE_NAME = "app"
    }
}
