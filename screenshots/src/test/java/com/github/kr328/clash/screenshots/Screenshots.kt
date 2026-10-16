package com.github.kr328.clash.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.common.util.AppLocale
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.util.toBytesString
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.screen.AboutState
import com.github.kr328.clash.design.compose.screen.AccessControlScreen
import com.github.kr328.clash.design.compose.screen.AccessControlState
import com.github.kr328.clash.design.compose.screen.AddProfileScreen
import com.github.kr328.clash.design.compose.screen.AddProfileState
import com.github.kr328.clash.design.compose.screen.AddProfileStep
import com.github.kr328.clash.design.compose.screen.AppSettingsScreen
import com.github.kr328.clash.design.compose.screen.AppSettingsState
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.screen.NetworkSettingsState
import com.github.kr328.clash.design.compose.screen.PropertiesScreen
import com.github.kr328.clash.design.compose.screen.PropertiesState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import com.github.kr328.clash.design.compose.screen.ReliabilityState
import com.github.kr328.clash.design.compose.screen.ServersState
import com.github.kr328.clash.design.compose.screen.SessionStats
import com.github.kr328.clash.design.compose.screen.SubScreen
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.compose.screen.SubscriptionsState
import com.github.kr328.clash.design.compose.screen.UpdateState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.service.R as ServiceR

private const val HOUR_MILLIS = 60 * 60 * 1000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val GIGABYTE = 1024L * 1024 * 1024
private const val DEAD = 0xffff

private const val MAIN = "Выбор сервера"
private const val AUTO = "Автовыбор"

private const val GERMANY = "🇩🇪 Германия"
private const val NETHERLANDS = "🇳🇱 Нидерланды"
private const val FINLAND = "🇫🇮 Финляндия"
private const val SWEDEN = "🇸🇪 Швеция"
private const val POLAND = "🇵🇱 Польша"
private const val KAZAKHSTAN = "🇰🇿 Казахстан"
private const val TURKEY = "🇹🇷 Турция"
private const val USA = "🇺🇸 США"
private const val JAPAN = "🇯🇵 Япония"
private const val BRITAIN = "🇬🇧 Великобритания"

private const val REALITY = "VLESS RAW (TCP) · Reality"
private const val XHTTP = "VLESS XHTTP · Reality"
private const val GRPC = "VLESS gRPC · Reality"
private const val HYSTERIA = "Hysteria2 (UDP)"
private const val TROJAN = "Trojan RAW (TCP) · TLS"

private fun node(name: String, protocol: String, delay: Int) = Proxy(name, name, protocol, "Vless", delay, false)

// current — сервер, на котором группа стоит: так её строку отдаёт ядро
private fun group(name: String, type: String, delay: Int, current: String = "") =
    Proxy(name, name, type, type, delay, true, current = current, currentTitle = current)

private val nodes = listOf(
    node(GERMANY, REALITY, 48),
    node(NETHERLANDS, XHTTP, 56),
    node(FINLAND, REALITY, 39),
    node(SWEDEN, HYSTERIA, 71),
    node(POLAND, GRPC, 63),
    node(KAZAKHSTAN, REALITY, 95),
    node(TURKEY, TROJAN, 142),
    node(USA, REALITY, 214),
    node(JAPAN, XHTTP, 438),
    node(BRITAIN, REALITY, DEAD),
)

private val groups = listOf(
    ProxyGroupState(
        name = MAIN,
        now = GERMANY,
        selectable = true,
        proxies = listOf(group(AUTO, "URLTest", 39, FINLAND)) + nodes,
    ),
    ProxyGroupState(
        name = AUTO,
        now = FINLAND,
        selectable = true,
        proxies = nodes,
        pinned = "",
    ),
    ProxyGroupState(
        name = "YouTube",
        now = NETHERLANDS,
        selectable = true,
        proxies = listOf(group(MAIN, "Selector", 48, GERMANY)) + nodes.take(5) + group("DIRECT", "Direct", 0),
    ),
    ProxyGroupState(
        name = "Нейросети",
        now = USA,
        selectable = true,
        proxies = listOf(group(MAIN, "Selector", 48, GERMANY)) + nodes,
    ),
    ProxyGroupState(
        name = "Российские сайты",
        now = "DIRECT",
        selectable = true,
        proxies = listOf(group("DIRECT", "Direct", 0), group(MAIN, "Selector", 48, GERMANY)),
    ),
)

private val panel = PanelInfo(
    title = "Demo VPN",
    announce = "Добавили серверы в Швеции и Польше. Если их нет в списке — обновите подписку.",
    supportUrl = "https://example.com/support",
    portalUrl = "https://example.com/account",
    botUrl = "https://example.com/bot",
    guideUrl = "https://example.com/guide",
    freezeCheck = true,
)

private fun profile(
    id: Int,
    name: String,
    active: Boolean,
    used: Long = 32 * GIGABYTE,
    total: Long = 100 * GIGABYTE,
    expire: Long = System.currentTimeMillis() + 24 * DAY_MILLIS,
    interval: Long = TimeUnit.HOURS.toMillis(12),
    fetchedAgo: Long = 2 * HOUR_MILLIS,
) = Profile(
    uuid = UUID.fromString("00000000-0000-0000-0000-00000000000$id"),
    name = name,
    type = Profile.Type.Url,
    source = "https://sub.example.com/$id",
    active = active,
    interval = interval,
    upload = used / 8,
    download = used - used / 8,
    total = total,
    expire = expire,
    imported = true,
    pending = false,
    secure = true,
    fetchedAt = System.currentTimeMillis() - fetchedAgo,
)

private val subscription = SubscriptionItem(profile = profile(1, "Demo VPN", active = true), panel = panel)

private val family = SubscriptionItem(
    profile = profile(2, "Семейная", active = false, used = 61 * GIGABYTE, total = 200 * GIGABYTE),
    panel = PanelInfo(title = "Семейная"),
)

private val spare = SubscriptionItem(
    profile = profile(
        3,
        "Запасная",
        active = false,
        used = 3 * GIGABYTE,
        total = 0,
        expire = System.currentTimeMillis() + 2 * DAY_MILLIS,
        interval = TimeUnit.HOURS.toMillis(1),
        fetchedAgo = 5 * HOUR_MILLIS,
    ),
    panel = PanelInfo(title = "Запасная"),
)

private val expired = SubscriptionItem(
    profile = profile(1, "Demo VPN", active = true, used = 41 * GIGABYTE, expire = System.currentTimeMillis() - 3 * DAY_MILLIS),
    panel = panel.copy(announce = "", noServers = true),
)

private val servers = ServersState(
    groups = groups,
    main = MAIN,
    favorites = setOf(GERMANY, FINLAND),
    freeze = mapOf(KAZAKHSTAN to "frozen"),
)

private val apps = listOf(
    "Банк" to 0xFF2E7D32,
    "Браузер" to 0xFF1565C0,
    "Видео" to 0xFFC62828,
    "Игры" to 0xFF6A1B9A,
    "Карты" to 0xFF00897B,
    "Магазин" to 0xFFEF6C00,
    "Мессенджер" to 0xFF0288D1,
    "Музыка" to 0xFFAD1457,
    "Новости" to 0xFF455A64,
    "Почта" to 0xFF5D4037,
    "Соцсеть" to 0xFF283593,
)

private fun appIcon(label: String, color: Long): Drawable {
    val size = 144
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawRoundRect(
        RectF(0f, 0f, size.toFloat(), size.toFloat()),
        size * 0.28f,
        size * 0.28f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() },
    )

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        textSize = size * 0.5f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    canvas.drawText(label.take(1), size / 2f, size / 2f - (text.descent() + text.ascent()) / 2, text)

    return BitmapDrawable(RuntimeEnvironment.getApplication().resources, bitmap)
}

private const val NOTES = """### Что нового
- Подключение после смены Wi-Fi на мобильную сеть восстанавливается быстрее
- Список серверов обновляется сразу после обновления подписки

### Исправления
- Уведомление о подписке больше не дублируется"""

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-rRU-w412dp-h915dp-xhdpi")
class Screenshots {
    @get:Rule
    val compose = createComposeRule()

    private fun session(seconds: Long) = SessionStats(
        seconds = seconds,
        downloaded = SESSION_DOWNLOADED.toBytesString(),
        uploaded = SESSION_UPLOADED.toBytesString(),
    )

    private fun state(
        status: ConnectionStatus = ConnectionStatus.Disconnected,
        tab: MainTab = MainTab.Home,
        active: SubscriptionItem = subscription,
        servers: ServersState = com.github.kr328.clash.screenshots.servers,
    ) = MainScreenState(
        status = status,
        active = active,
        selectedTab = tab,
        servers = servers,
        subscriptions = SubscriptionsState(profiles = listOf(subscription, family, spare)),
        about = AboutState(versionName = VERSION, coreVersion = CORE_VERSION),
    )

    private fun setContent(dark: Boolean, height: Int, content: @Composable () -> Unit) {
        AppLocale.current = Locale.forLanguageTag("ru")
        compose.mainClock.autoAdvance = false

        compose.setContent {
            ClodClashTheme(darkTheme = dark) {
                Surface(modifier = Modifier.size(412.dp, height.dp)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }

        compose.mainClock.advanceTimeBy(1000)
    }

    private fun shoot(name: String, dark: Boolean = false, height: Int = 915, content: @Composable () -> Unit) {
        setContent(dark, height, content)

        compose.onRoot().captureRoboImage(filePath = "build/screenshots/ru/$name.png")
    }

    // Диалоги и нижние панели живут в своих окнах — снимается весь экран
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shootScreen(name: String, content: @Composable () -> Unit) {
        setContent(dark = false, height = 915, content)

        captureScreenRoboImage(filePath = "build/screenshots/ru/$name.png")
    }

    @Test
    fun home() = shoot("home") {
        MainScreen(state = state(), onAction = {})
    }

    @Test
    fun connected() = shoot("connected") {
        MainScreen(state = state(status = ConnectionStatus.Connected), onAction = {}, session = { session(SESSION_SECONDS) })
    }

    @Test
    fun connectedDark() = shoot("connected-dark", dark = true) {
        MainScreen(state = state(status = ConnectionStatus.Connected), onAction = {}, session = { session(SESSION_SECONDS) })
    }

    @Test
    fun connecting() = shoot("connecting") {
        MainScreen(state = state(status = ConnectionStatus.Connecting).copy(startupStage = null), onAction = {})
    }

    @Test
    fun homeAllGroups() = shoot("home-all-groups") {
        MainScreen(
            state = state(status = ConnectionStatus.Connected, servers = servers.copy(allOnHome = true)),
            onAction = {},
            session = { session(SESSION_SECONDS) },
        )
    }

    @Test
    fun servers() = shoot("servers") {
        MainScreen(state = state(status = ConnectionStatus.Connected, tab = MainTab.Servers), onAction = {})
    }

    @Test
    fun serversAuto() = shoot("servers-auto") {
        val pinned = groups.map { if (it.name == AUTO) it.copy(now = GERMANY, pinned = GERMANY) else it }

        MainScreen(
            state = state(
                status = ConnectionStatus.Connected,
                tab = MainTab.Servers,
                servers = servers.copy(groups = pinned, selected = 1),
            ),
            onAction = {},
        )
    }

    @Test
    fun serversOffline() = shoot("servers-offline") {
        // Без туннеля автовыбор сервера не выбирает: у его строки ни сервера, ни задержки
        val offline = groups.map { group ->
            group.copy(proxies = group.proxies.map { if (it.name == AUTO) it.copy(subtitle = "", type = "", delay = 0, current = "", currentTitle = "") else it })
        }

        MainScreen(state = state(tab = MainTab.Servers, servers = servers.copy(groups = offline, offline = true)), onAction = {})
    }

    @Test
    fun subscriptions() = shoot("subscriptions") {
        MainScreen(state = state(tab = MainTab.Subscriptions), onAction = {})
    }

    @Test
    @Config(qualifiers = "+h1250dp")
    fun more() = shoot("more", height = 1250) {
        MainScreen(state = state(tab = MainTab.More), onAction = {})
    }

    @Test
    fun about() = shoot("about") {
        MainScreen(state = state(tab = MainTab.More).copy(subScreen = SubScreen.About), onAction = {})
    }

    @Test
    fun update() = shootScreen("update") {
        MainScreen(
            state = state().copy(update = UpdateState(version = UPDATE_VERSION, sizeBytes = UPDATE_SIZE, notes = NOTES)),
            onAction = {},
        )
    }

    @Test
    fun reliability() = shootScreen("reliability") {
        MainScreen(
            state = state(status = ConnectionStatus.Connected).copy(reliability = ReliabilityState(prompt = true)),
            onAction = {},
            session = { session(42) },
        )
    }

    @Test
    fun freeze() = shootScreen("freeze") {
        MainScreen(
            state = state(status = ConnectionStatus.Connected, tab = MainTab.Servers).copy(freezeSheet = true),
            onAction = {},
        )
    }

    @Test
    fun expired() = shoot("expired") {
        MainScreen(state = state(active = expired, servers = ServersState()), onAction = {})
    }

    @Test
    fun addProfile() = shoot("add-profile") {
        AddProfileScreen(state = AddProfileState(url = SOURCE), onAction = {})
    }

    @Test
    fun addProfileChecking() = shoot("add-profile-checking") {
        AddProfileScreen(
            state = AddProfileState(
                url = SOURCE,
                step = AddProfileStep.Fetching,
                stageText = stringResource(R.string.clod_chan_stage_checking),
            ),
            onAction = {},
        )
    }

    @Test
    fun addProfileError() = shoot("add-profile-error") {
        AddProfileScreen(
            state = AddProfileState(url = SOURCE, error = stringResource(ServiceR.string.clod_update_cause_not_found, 404)),
            onAction = {},
        )
    }

    @Test
    fun properties() = shoot("properties") {
        PropertiesScreen(
            state = PropertiesState(
                nameShown = "Demo VPN",
                url = SOURCE,
                intervalMinutes = "720",
                secure = true,
                secureEditable = true,
                chanKey = "5f3a·9c1e·d24b·07aa",
            ),
            onAction = {},
        )
    }

    @Test
    fun apps() = shoot("apps") {
        val list = apps.mapIndexed { index, (label, color) ->
            AppInfo(
                packageName = "com.example." + "app$index",
                label = label,
                icon = appIcon(label, color),
                installTime = 0,
                updateDate = 0,
            )
        }

        AccessControlScreen(
            state = AccessControlState(
                apps = list,
                selected = setOf("com.example.app1", "com.example.app2", "com.example.app6", "com.example.app10"),
                loaded = true,
                mode = 1,
            ),
            onAction = {},
        )
    }

    @Test
    fun appsEmpty() = shoot("apps-empty") {
        val list = apps.mapIndexed { index, (label, color) ->
            AppInfo("com.example.app$index", label, appIcon(label, color), 0, 0)
        }

        AccessControlScreen(state = AccessControlState(apps = list, loaded = true, mode = 1), onAction = {})
    }

    @Test
    @Config(qualifiers = "+h2300dp")
    fun appSettings() = shoot("app-settings", height = 2300) {
        AppSettingsScreen(state = AppSettingsState(autoRestart = true), onAction = {})
    }

    @Test
    @Config(qualifiers = "+h1750dp")
    fun network() = shoot("network", height = 1750) {
        NetworkSettingsScreen(
            state = NetworkSettingsState(localProxyPort = 7890, effectiveTunStack = "mips"),
            onAction = {},
        )
    }

    private companion object {
        private const val SOURCE = "https://sub.example.com/a1b2c3d4"
        private const val VERSION = "0.1.23"
        private const val CORE_VERSION = "v1.19.32-clod.5"
        private const val UPDATE_VERSION = "0.1.24"
        private const val UPDATE_SIZE = 38L * 1024 * 1024

        private const val SESSION_SECONDS = 3L * 60 * 60 + 12 * 60 + 5
        private const val SESSION_DOWNLOADED = 1_503_238_554L
        private const val SESSION_UPLOADED = 212L * 1024 * 1024
    }
}
