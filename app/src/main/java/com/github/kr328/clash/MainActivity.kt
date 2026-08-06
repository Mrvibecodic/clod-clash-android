package com.github.kr328.clash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.update.UpdatePrompt
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.service.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        // Обновление приложения из GitHub Releases. Ядро отдельно не обновляется:
        // оно вкомпилировано в APK, и подменить его по одному файлу нельзя.
        UpdatePrompt.checkInBackground(this, this)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.ReloadProxies -> design.reloadProxyGroups()
                        is MainDesign.Request.ReloadGroup ->
                            design.reloadProxyGroup(request.index)
                        is MainDesign.Request.SelectProxy -> {
                            proxyGroupNames.getOrNull(request.index)?.let { group ->
                                withClash { patchSelector(group, request.name) }

                                design.reloadProxyGroup(request.index)
                            }
                        }
                        is MainDesign.Request.UrlTest -> {
                            proxyGroupNames.getOrNull(request.index)?.let { group ->
                                // В отдельной корутине: проверка группы занимает
                                // секунды, а цикл событий должен остаться живым —
                                // иначе на это время замирает и кнопка подключения.
                                launch {
                                    design.setProxyTesting(true)

                                    try {
                                        withClash { healthCheck(group) }

                                        design.reloadProxyGroup(request.index)
                                    } finally {
                                        design.setProxyTesting(false)
                                    }
                                }
                            }
                        }
                        is MainDesign.Request.PatchMode -> {
                            withClash {
                                val override = queryOverride(Clash.OverrideSlot.Session)

                                override.mode = request.mode

                                patchOverride(Clash.OverrideSlot.Session, override)
                            }

                            design.fetch()
                        }
                        MainDesign.Request.NewProfile ->
                            startActivity(AddProfileActivity::class.intent)
                        MainDesign.Request.UpdateAllProfiles -> {
                            // Отдельная корутина: обновление всех подписок ходит
                            // в сеть по очереди, а цикл событий должен жить.
                            launch {
                                design.setProfilesUpdating(true)

                                try {
                                    withProfile {
                                        queryAll().forEach { profile ->
                                            if (profile.imported &&
                                                profile.type != Profile.Type.File
                                            ) {
                                                update(profile.uuid)
                                            }
                                        }
                                    }
                                } finally {
                                    design.setProfilesUpdating(false)
                                }
                            }
                        }
                        is MainDesign.Request.ActivateProfile -> {
                            val profile = request.profile

                            if (profile.imported) {
                                withProfile { setActive(profile) }
                            } else {
                                // Профиль ещё не сохранён: активировать нечего,
                                // ведём в редактор, как это делал старый экран.
                                design.showToast(
                                    DesignR.string.active_unsaved_tips,
                                    ToastDuration.Long,
                                ) {
                                    setAction(DesignR.string.edit) {
                                        startActivity(
                                            PropertiesActivity::class.intent
                                                .setUUID(profile.uuid),
                                        )
                                    }
                                }
                            }
                        }
                        is MainDesign.Request.UpdateProfile ->
                            withProfile { update(request.profile.uuid) }
                        is MainDesign.Request.EditProfile ->
                            startActivity(
                                PropertiesActivity::class.intent.setUUID(request.profile.uuid),
                            )
                        is MainDesign.Request.DeleteProfile ->
                            withProfile { delete(request.profile.uuid) }
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
            setProfiles(queryAll())
        }

        reloadProxyGroups()
    }

    /**
     * Имена групп в том же порядке, в каком они лежат в состоянии экрана: запросы
     * от экрана приходят с индексом, а ядру нужно имя.
     */
    private var proxyGroupNames: List<String> = emptyList()

    private suspend fun MainDesign.reloadProxyGroups() {
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }

        proxyGroupNames = names

        setProxyGroupNames(names)

        if (names.isNotEmpty()) {
            reloadProxyGroup(selectedGroup)
        }
    }

    private suspend fun MainDesign.reloadProxyGroup(index: Int) {
        val name = proxyGroupNames.getOrNull(index) ?: return
        val group = withClash { queryProxyGroup(name, uiStore.proxySort) }

        // Выбор руками осмыслен только у Selector: в url-test и fallback узел
        // назначает ядро, и patchSelector там молча ничего не делает.
        setProxyGroup(index, group.now, group.type == "Selector", group.proxies)
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setTraffic(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    launch { selectTab(MainTab.Subscriptions) }
                }
            }

            return
        }

        // Ставим «Подключение…» до похода в службу: поднятие туннеля занимает
        // заметное время, и без этого первое нажатие выглядит как непрошедшее.
        setConnecting()

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                } else {
                    // Пользователь отказал в разрешении на VPN. События от службы
                    // не будет, поэтому «Подключение…» надо снять руками — иначе
                    // экран так и останется в промежуточном состоянии.
                    setClashRunning(clashRunning)
                }
            }
        } catch (e: Exception) {
            setClashRunning(clashRunning)
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}
