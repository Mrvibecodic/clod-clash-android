package com.github.kr328.clash

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.applyDynamicShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    private val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun main() {
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
            { clashRunning },
            ::onReset,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        Event.ActivityStart -> design.refreshNotifications()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppSettingsDesign.Request.ReCreateAllActivities ->
                            ApplicationObserver.createdActivities.forEach { activity ->
                                activity.recreate()
                            }

                        AppSettingsDesign.Request.OpenSystemNotifications ->
                            openNotificationSettings()

                        AppSettingsDesign.Request.RequestNotifications ->
                            requestNotifications()

                        AppSettingsDesign.Request.ExportProfiles ->
                            exportProfiles(design)

                        AppSettingsDesign.Request.ImportProfiles ->
                            importProfiles(design)

                        AppSettingsDesign.Request.Back -> finish()
                    }
                }
            }
        }
    }

    @Serializable
    private data class BackupProfile(
        val name: String,
        val source: String,
        val interval: Long = 0,
        val secure: Boolean = false,
        val active: Boolean = false,
    )

    @Serializable
    private data class Backup(
        val version: Int = BACKUP_VERSION,
        val profiles: List<BackupProfile> = emptyList(),
    )

    private suspend fun exportProfiles(design: AppSettingsDesign) {
        val profiles = withProfile { queryAll() }
            .filter { it.type == Profile.Type.Url && it.source.isNotBlank() }

        if (profiles.isEmpty()) {
            design.showToast(DesignR.string.clod_backup_empty, ToastDuration.Long)

            return
        }

        val backup = Backup(
            profiles = profiles.map {
                BackupProfile(
                    name = it.name,
                    source = it.source,
                    interval = it.interval,
                    secure = it.secure,
                    active = it.active,
                )
            },
        )

        val output = startActivityForResult(
            ActivityResultContracts.CreateDocument("application/json"),
            BACKUP_FILE_NAME,
        ) ?: return

        try {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(output, "wt")
                    ?: throw IllegalStateException(output.toString())

                stream.use {
                    it.write(backupJson.encodeToString(Backup.serializer(), backup).toByteArray())
                }
            }

            design.showToast(DesignR.string.clod_backup_saved, ToastDuration.Long)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun importProfiles(design: AppSettingsDesign) {
        val input = startActivityForResult(
            ActivityResultContracts.GetContent(),
            "application/json",
        ) ?: return

        val backup = try {
            val content = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(input)?.use { it.readBytes().decodeToString() }
            } ?: return

            backupJson.decodeFromString(Backup.serializer(), content)
        } catch (e: Exception) {
            Log.w("Read subscriptions backup: $e", e)

            design.showToast(DesignR.string.clod_backup_invalid, ToastDuration.Long)

            return
        }

        val wanted = backup.profiles.filter { it.source.isNotBlank() }

        if (backup.version > BACKUP_VERSION || wanted.isEmpty()) {
            design.showToast(DesignR.string.clod_backup_invalid, ToastDuration.Long)

            return
        }

        val known = withProfile { queryAll() }.map { it.source }.toSet()

        var restored = 0

        for (item in wanted) {
            if (item.source in known) continue

            val uuid = withProfile {
                create(Profile.Type.Url, item.name, item.source, secure = item.secure)
            }

            var committed = false

            try {
                if (item.interval > 0) {
                    withProfile { patch(uuid, item.name, item.source, item.interval, null) }
                }

                withProfile { commit(uuid) }

                committed = true

                val profile = withProfile { queryByUUID(uuid) } ?: throw IllegalStateException()

                if (item.active && withProfile { queryActive() } == null) {
                    withProfile { setActive(profile) }
                }

                restored += 1
            } catch (e: Exception) {
                Log.w("Restore subscription: $e", e)

                if (!committed) {
                    withProfile { release(uuid) }
                }
            }
        }

        design.showToast(
            getString(DesignR.string.clod_backup_restored, restored, wanted.size),
            ToastDuration.Long,
        )
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private suspend fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return

        try {
            startActivityForResult(
                RequestPermission(),
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Request notifications: $e", e)
        }

        uiStore.notificationsAsked = true

        design?.refreshNotifications()
    }

    private fun openNotificationSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)),
        )

        for (intent in intents) {
            try {
                startActivity(intent)

                return
            } catch (e: Exception) {
                Log.w("Open notification settings: $e", e)
            }
        }
    }

    private fun onReset() {
        AppStore(this).apply {
            autoCheckUpdate = true
            prereleaseChannel = false
            skippedVersionCode = 0
        }
    }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            newState,
            PackageManager.DONT_KILL_APP
        )

        applyDynamicShortcuts(hide)
    }

    private companion object {
        private const val BACKUP_VERSION = 1
        private const val BACKUP_FILE_NAME = "clodclash-subscriptions.json"
    }
}
