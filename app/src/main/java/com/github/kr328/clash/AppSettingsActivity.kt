package com.github.kr328.clash

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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.github.kr328.clash.design.compose.screen.MIN_INTERVAL_MINUTES
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.design.util.ValidatorHttpUrl
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.ProfileImports
import com.github.kr328.clash.util.applyDynamicShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

        launch {
            ProfileImports.batch.collect { state ->
                if (state is ProfileImports.BatchState.Done) {
                    ProfileImports.resetBatch()

                    design.showToast(
                        getString(DesignR.string.clod_backup_restored, state.restored, state.total),
                        ToastDuration.Long,
                    )
                }
            }
        }

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
            withContext(Dispatchers.IO) {
                val content = contentResolver.openInputStream(input)?.use { stream ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)

                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break

                        if (bytes.size() + read > BACKUP_MAX_BYTES) {
                            throw IllegalArgumentException("backup exceeds $BACKUP_MAX_BYTES bytes")
                        }

                        bytes.write(buffer, 0, read)
                    }

                    bytes.toByteArray().decodeToString()
                } ?: return@withContext null

                backupJson.decodeFromString(Backup.serializer(), content)
            } ?: return
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

        val items = wanted.mapNotNull { item ->
            val source = item.source.trim()

            if (!ValidatorHttpUrl(source) || source in known) return@mapNotNull null

            ProfileImports.Item(
                name = item.name,
                source = source,
                interval = if (item.interval > 0) {
                    maxOf(item.interval, TimeUnit.MINUTES.toMillis(MIN_INTERVAL_MINUTES))
                } else {
                    0L
                },
                secure = item.secure,
                active = item.active,
            )
        }

        ProfileImports.startBatch(items, wanted.size)
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
        private const val BACKUP_MAX_BYTES = 1024 * 1024
        private const val BACKUP_FILE_NAME = "clodclash-subscriptions.json"
    }
}
