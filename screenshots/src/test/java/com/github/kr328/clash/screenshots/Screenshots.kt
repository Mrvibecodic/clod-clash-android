package com.github.kr328.clash.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.screen.AddProfileScreen
import com.github.kr328.clash.design.compose.screen.AddProfileState
import com.github.kr328.clash.design.compose.screen.MainScreen
import com.github.kr328.clash.design.compose.screen.MainScreenState
import com.github.kr328.clash.design.compose.screen.MainTab
import com.github.kr328.clash.design.compose.screen.NetworkSettingsScreen
import com.github.kr328.clash.design.compose.screen.NetworkSettingsState
import com.github.kr328.clash.design.compose.screen.ProxyGroupState
import com.github.kr328.clash.design.compose.screen.ServersState
import com.github.kr328.clash.design.compose.screen.SubscriptionItem
import com.github.kr328.clash.design.compose.screen.SubscriptionsState
import com.github.kr328.clash.design.compose.theme.ClodClashTheme
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
private const val GIGABYTE = 1000L * 1000 * 1000

class DemoContent(
    val subscription: String,
    val servers: List<Pair<String, String>>,
    val downloaded: String,
    val uploaded: String,
    val source: String = "https://example.com/subscription",
)

private val russian = DemoContent(
    subscription = "Моя подписка",
    servers = listOf(
        "Нидерланды 01" to "Амстердам",
        "Германия 02" to "Франкфурт",
        "Финляндия 03" to "Хельсинки",
        "Швеция 04" to "Стокгольм",
        "Франция 05" to "Париж",
    ),
    downloaded = "1,4 ГБ",
    uploaded = "212 МБ",
)

private val english = DemoContent(
    subscription = "My subscription",
    servers = listOf(
        "Netherlands 01" to "Amsterdam",
        "Germany 02" to "Frankfurt",
        "Finland 03" to "Helsinki",
        "Sweden 04" to "Stockholm",
        "France 05" to "Paris",
    ),
    downloaded = "1.4 GB",
    uploaded = "212 MB",
)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
abstract class Screenshots(private val locale: String, private val demo: DemoContent) {
    @get:Rule
    val compose = createComposeRule()

    private val profile = Profile(
        uuid = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        name = demo.subscription,
        type = Profile.Type.Url,
        source = demo.source,
        active = true,
        interval = TimeUnit.HOURS.toMillis(12),
        upload = 4 * GIGABYTE,
        download = 28 * GIGABYTE,
        total = 100 * GIGABYTE,
        expire = System.currentTimeMillis() + 24 * DAY_MILLIS,
        updatedAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000,
        imported = true,
        pending = false,
    )

    private val subscription = SubscriptionItem(
        profile = profile,
        panel = PanelInfo(title = demo.subscription),
    )

    private val servers = ServersState(
        groups = listOf(
            ProxyGroupState(
                name = "Servers",
                now = demo.servers.first().first,
                selectable = true,
                proxies = demo.servers.mapIndexed { index, (name, city) ->
                    Proxy(name, name, city, "Vless", DELAYS[index], false)
                },
            ),
        ),
    )

    private fun state(
        status: ConnectionStatus = ConnectionStatus.Disconnected,
        tab: MainTab = MainTab.Home,
        session: Long = 0,
        traffic: Boolean = false,
    ) = MainScreenState(
        status = status,
        active = subscription,
        mode = TunnelState.Mode.Rule,
        selectedTab = tab,
        sessionSeconds = session,
        downloaded = if (traffic) demo.downloaded else "",
        uploaded = if (traffic) demo.uploaded else "",
        servers = servers,
        subscriptions = SubscriptionsState(profiles = listOf(subscription)),
    )

    private fun shoot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false

        compose.setContent {
            ClodClashTheme(darkTheme = dark) {
                Surface(modifier = Modifier.size(412.dp, 915.dp)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }

        compose.mainClock.advanceTimeBy(500)

        compose.onRoot().captureRoboImage(filePath = "build/screenshots/$locale/$name.png")
    }

    @Test
    fun home() = shoot("home") {
        MainScreen(state = state(), onAction = {})
    }

    @Test
    fun connected() = shoot("connected") {
        MainScreen(
            state = state(status = ConnectionStatus.Connected, session = SESSION_SECONDS, traffic = true),
            onAction = {},
        )
    }

    @Test
    fun connectedDark() = shoot("connected-dark", dark = true) {
        MainScreen(
            state = state(status = ConnectionStatus.Connected, session = SESSION_SECONDS, traffic = true),
            onAction = {},
        )
    }

    @Test
    fun servers() = shoot("servers") {
        MainScreen(state = state(status = ConnectionStatus.Connected, tab = MainTab.Servers), onAction = {})
    }

    @Test
    fun subscriptions() = shoot("subscriptions") {
        MainScreen(state = state(tab = MainTab.Subscriptions), onAction = {})
    }

    @Test
    fun more() = shoot("more") {
        MainScreen(state = state(tab = MainTab.More), onAction = {})
    }

    @Test
    fun addProfile() = shoot("add-profile") {
        AddProfileScreen(state = AddProfileState(url = demo.source), onAction = {})
    }

    @Test
    fun network() = shoot("network") {
        NetworkSettingsScreen(state = NetworkSettingsState(localProxyPort = 7890), onAction = {})
    }

    private companion object {
        private val DELAYS = listOf(74, 96, 118, 143, 165)

        private const val SESSION_SECONDS = 3L * 60 * 60 + 12 * 60 + 5
    }
}

@Config(qualifiers = "ru-rRU-w412dp-h915dp-xxhdpi")
class RussianScreenshots : Screenshots("ru", russian)

@Config(qualifiers = "en-rUS-w412dp-h915dp-xxhdpi")
class EnglishScreenshots : Screenshots("en", english)
