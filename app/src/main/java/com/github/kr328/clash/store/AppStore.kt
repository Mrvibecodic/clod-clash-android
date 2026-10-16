package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class AppStore private constructor(context: Context, mode: Int) {
    constructor(context: Context) : this(context, Context.MODE_PRIVATE)

    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, mode)
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

    var awaitingInstallPermission: Boolean by store.boolean(
        key = "awaiting_install_permission",
        defaultValue = false,
    )

    var addedProfilePending: Boolean by store.boolean(
        key = "added_profile_pending",
        defaultValue = false,
    )

    var addedProfileName: String by store.string(
        key = "added_profile_name",
        defaultValue = "",
    )

    var addedProfileExisting: Boolean by store.boolean(
        key = "added_profile_existing",
        defaultValue = false,
    )

    // Пришла ли добавленная подписка защищённым каналом — для сообщения «добавлена».
    var addedProfileSecure: Boolean by store.boolean(
        key = "added_profile_secure",
        defaultValue = false,
    )

    // Добавление не удалось, а экран добавления к тому времени закрыли: причину
    // покажет Главная. Рядом с итогом удачного добавления, а не в общем ходе
    // загрузки — его перезаписало бы следующее сохранение свойств
    var addFailedMessage: String by store.string(
        key = "add_failed_message",
        defaultValue = "",
    )

    var addFailedDetail: String by store.string(
        key = "add_failed_detail",
        defaultValue = "",
    )

    var profileProvidersFailed: String by store.string(
        key = "profile_providers_failed",
        defaultValue = "",
    )

    companion object {
        private const val FILE_NAME = "app"

        // Файл пишет процесс интерфейса; :background без этого флага до своего
        // перезапуска читал бы снимок, взятый при первом обращении.
        @Suppress("DEPRECATION")
        fun readFromBackground(context: Context): AppStore = AppStore(context, Context.MODE_MULTI_PROCESS)
    }
}
