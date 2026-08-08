package com.github.kr328.clash.design.compose.screen

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ActionRow
import com.github.kr328.clash.design.compose.component.ConnectionStatus
import com.github.kr328.clash.design.compose.component.PowerButton
import com.github.kr328.clash.design.compose.component.SectionHeader
import com.github.kr328.clash.design.compose.component.NoServersCard
import com.github.kr328.clash.design.compose.component.SelectorRow
import com.github.kr328.clash.design.compose.component.SyncIconButton
import com.github.kr328.clash.design.compose.component.noServersReason
import com.github.kr328.clash.design.compose.component.TrafficCard
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.StatusTextStyle
import com.github.kr328.clash.design.compose.theme.TimerTextStyle
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import java.util.UUID

/** Вкладки нижней навигации. Порядок совпадает с утверждённым макетом. */
enum class MainTab {
    Home,
    Servers,
    Subscriptions,
    More,
}

/**
 * Вложенные экраны вкладки «Ещё». Открываются поверх содержимого вкладки,
 * нижняя навигация остаётся на месте — уйти с экрана можно и по стрелке,
 * и переключением вкладки.
 */
enum class SubScreen {
    About,
    RoutingData,
}

/**
 * Одна группа прокси в том виде, в каком её показывает вкладка «Серверы».
 *
 * @param selectable группа типа Selector — узел в ней можно выбрать руками.
 *   В url-test и fallback узел выбирает ядро, и патч селектора там не сработает.
 */
data class ProxyGroupState(
    val name: String,
    val now: String,
    val selectable: Boolean,
    val proxies: List<Proxy>,
)

/**
 * Состояние вкладки «Серверы».
 *
 * @param offline список собран из файла подписки, а не из работающего ядра:
 *   задержек нет и выбрать узел нельзя, пока туннель не поднят.
 */
data class ServersState(
    val groups: List<ProxyGroupState> = emptyList(),
    val selected: Int = 0,
    val testing: Boolean = false,
    val offline: Boolean = false,
    /**
     * Список из файла, но ядро при этом работает: так бывает в режиме
     * «Прямое соединение», где ядро групп не отдаёт вовсе. Мерить задержки
     * своим разбором конфига поверх живого ядра нельзя, а запоминать выбор
     * «на потом» бессмысленно — человек уже подключён.
     */
    val readOnly: Boolean = false,
    /**
     * Имена узлов, отмеченных звездой. Хранятся отдельно от списка, а не полем
     * в [Proxy]: список приходит от ядра, а отметки — наши, и мешать их значило
     * бы перекладывать набор на каждую перерисовку.
     */
    val favorites: Set<String> = emptySet(),
)

/**
 * Подписка в том виде, в каком её показывает список: сам профиль плюс то,
 * что панель прислала заголовками (название, ссылки, объявления).
 */
data class SubscriptionItem(
    val profile: Profile,
    val panel: PanelInfo? = null,
    /** Пользовательская группа («Личные», «Работа»); null — без группы. */
    val group: String? = null,
    /**
     * Абсолютный путь к логотипу провайдера (`profile-logo`), уже скачанному
     * ядром в каталог профиля; null — показывать значок приложения.
     */
    val logoPath: String? = null,
) {
    /** Название от панели, а если его нет — то, под которым профиль сохранён. */
    val title: String
        get() = panel?.title?.takeIf { it.isNotBlank() } ?: profile.name

    /**
     * Поправка к часам устройства по часам панели, в миллисекундах.
     *
     * Всё, что считает «сколько осталось», должно спрашивать её, а не системные
     * часы напрямую: на телефоне со сбитым временем срок подписки показывался
     * бы неверно, а напоминания приходили бы не в тот день.
     */
    fun panelClockSkew(): Long = panel?.clockSkewMillis() ?: 0
}

/** Состояние вкладки «Подписки». */
data class SubscriptionsState(
    val profiles: List<SubscriptionItem> = emptyList(),
    /**
     * Подписки, которые прямо сейчас обновляются.
     *
     * Именно множество, а не общий флаг: обновление идёт в служебном процессе
     * (`ProfileWorker`), запускается по одной подписке и заканчивается
     * широковещательным сообщением с её uuid. Пока крутится одна карточка,
     * остальные должны оставаться живыми.
     */
    val updatingUuids: Set<UUID> = emptySet(),
    /** Выбранный фильтр по группе; null — показывать все. */
    val selectedGroup: String? = null,
) {
    /** Идёт ли обновление хоть чего-нибудь: для кнопки «обновить всё» в шапке. */
    val updating: Boolean
        get() = updatingUuids.isNotEmpty()
}

/**
 * Всё, что показывает главный экран. Отдельный неизменяемый снимок вместо
 * россыпи параметров: экран перерисовывается одним `setState`, и ни один
 * промежуточный кадр не может застать половину полей обновлёнными.
 */
data class MainScreenState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    /** Активная подписка вместе с данными панели. Null — ни одной не выбрано. */
    val active: SubscriptionItem? = null,
    val mode: TunnelState.Mode = TunnelState.Mode.Rule,
    val downloaded: String = "",
    val uploaded: String = "",
    /** Длительность текущей сессии в секундах; 0 — таймер не показывать. */
    val sessionSeconds: Long = 0,
    val hasProviders: Boolean = false,
    val selectedTab: MainTab = MainTab.Home,
    /** Открытый вложенный экран; null — показывать саму вкладку. */
    val subScreen: SubScreen? = null,
    val servers: ServersState = ServersState(),
    val subscriptions: SubscriptionsState = SubscriptionsState(),
    val about: AboutState = AboutState(),
    val routingData: RoutingDataState = RoutingDataState(),
    /** Найденное обновление; null — окно не показывать. */
    val update: UpdateState? = null,
)

/** Действия пользователя. Экран сам ничего не делает — только сообщает наверх. */
sealed interface MainAction {
    data object ToggleStatus : MainAction
    data object OpenProviders : MainAction
    data object OpenAccessControl : MainAction
    data object OpenLogs : MainAction
    data object OpenAppSettings : MainAction
    data object OpenNetworkSettings : MainAction
    data object OpenOverrideSettings : MainAction
    data object OpenMetaSettings : MainAction
    data object OpenHelp : MainAction
    data class OpenSubScreen(val screen: SubScreen) : MainAction
    data object CloseSubScreen : MainAction
    data object TestDelays : MainAction
    data class SetMode(val mode: TunnelState.Mode) : MainAction
    data class SelectTab(val tab: MainTab) : MainAction
    data class SelectGroup(val index: Int) : MainAction
    data class SelectProxy(val name: String) : MainAction
    data class ToggleFavorite(val name: String) : MainAction

    data class OpenUrl(val url: String) : MainAction
    data object CheckUpdate : MainAction
    data object UpdateNow : MainAction
    data object UpdateLater : MainAction
    data object UpdateSkip : MainAction
    data class SetAutoCheckUpdate(val enabled: Boolean) : MainAction
    data class SetPrerelease(val enabled: Boolean) : MainAction
    data object UpdateRoutingData : MainAction
    data class SelectSubscriptionGroup(val group: String?) : MainAction
    data class SetSubscriptionGroup(val profile: Profile, val group: String?) : MainAction
    data object NewProfile : MainAction
    data object UpdateAllProfiles : MainAction
    data class ActivateProfile(val profile: Profile) : MainAction
    data class UpdateProfile(val profile: Profile) : MainAction
    data class EditProfile(val profile: Profile) : MainAction
    data class DeleteProfile(val profile: Profile) : MainAction
}

@Composable
fun MainScreen(
    state: MainScreenState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { MainBottomBar(state.selectedTab, onAction) },
    ) { padding ->
        state.update?.let { UpdateDialog(it, onAction) }

        Box(modifier = Modifier.padding(padding)) {
            when (state.subScreen) {
                SubScreen.About -> AboutScreen(state.about, onAction)
                SubScreen.RoutingData -> RoutingDataScreen(state.routingData, onAction)
                null -> when (state.selectedTab) {
                    MainTab.Servers -> ServersTab(state.servers, state.active, onAction)
                    MainTab.Subscriptions -> SubscriptionsTab(state.subscriptions, onAction)
                    MainTab.More -> MoreTab(state, onAction)
                    else -> HomeTab(state, onAction)
                }
            }
        }
    }
}

@Composable
private fun MainBottomBar(selected: MainTab, onAction: (MainAction) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        MainTab.entries.forEach { tab ->
            val (labelRes, iconRes) = when (tab) {
                MainTab.Home -> R.string.clod_tab_home to R.drawable.ic_nav_home
                MainTab.Servers -> R.string.clod_tab_servers to R.drawable.ic_nav_servers
                MainTab.Subscriptions ->
                    R.string.clod_tab_subscriptions to R.drawable.ic_baseline_view_list

                MainTab.More -> R.string.clod_tab_more to R.drawable.ic_baseline_settings
            }
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onAction(MainAction.SelectTab(tab)) },
                icon = {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun HomeTab(state: MainScreenState, onAction: (MainAction) -> Unit) {
    val connected = state.status == ConnectionStatus.Connected
    // Одна анимируемая величина на всё раскрытие экрана: карточки, размер кнопки
    // и отступы двигаются синхронно, потому что читают её же.
    val expansion by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "homeExpansion",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        MainHeader(
            profileName = state.active?.title,
            logoPath = state.active?.logoPath,
            updating = state.subscriptions.updating,
            onAction = onAction,
        )

        state.active?.let { active ->
            PanelBanner(active, onAction)

            // Карточка подписки на главном — только когда она к месту: во время
            // сессии (человек ради этого и открыл приложение) или когда с ней
            // что-то не так. В спокойном отключённом состоянии экран должен
            // оставаться пустым вокруг кнопки, как в макете.
            ActiveSubscriptionCard(
                item = active,
                expanded = connected,
                // Когда сверху висит карточка «серверов нет», кнопки уже есть
                // у неё, и повторять их ниже незачем.
                showActions = noServersReason(active.profile, active.panel) == null,
                onAction = onAction,
            )
        }

        AnimatedVisibility(
            visible = connected,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrafficCard(
                    label = stringResource(R.string.clod_traffic_downloaded),
                    value = state.downloaded,
                    icon = painterResource(R.drawable.ic_traffic_down),
                    modifier = Modifier.weight(1f),
                )
                TrafficCard(
                    label = stringResource(R.string.clod_traffic_uploaded),
                    value = state.uploaded,
                    icon = painterResource(R.drawable.ic_traffic_up),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height((40 - 16 * expansion).dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PowerButton(
                status = state.status,
                onClick = { onAction(MainAction.ToggleStatus) },
                diameter = (148 - 28 * expansion).dp,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(
                    when (state.status) {
                        ConnectionStatus.Disconnected -> R.string.clod_status_disconnected
                        ConnectionStatus.Connecting -> R.string.clod_status_connecting
                        ConnectionStatus.Connected -> R.string.clod_status_connected
                    },
                ),
                style = StatusTextStyle,
                color = when (state.status) {
                    ConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurface
                    ConnectionStatus.Connecting -> ClodTheme.extraColors.statusConnecting
                    ConnectionStatus.Connected -> ClodTheme.extraColors.statusConnected
                },
                textAlign = TextAlign.Center,
            )
            if (!connected) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.clod_tap_to_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.sessionSeconds > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatSession(state.sessionSeconds),
                    style = TimerTextStyle,
                    color = ClodTheme.extraColors.statusConnected,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // Подписью строки идёт НАСТОЯЩЕЕ имя группы, а не слово «Группа»: то, что
        // это строка выбора, и так видно по стрелке, а вот в какой группе выбран
        // узел — больше нигде на главном не написано. Групп нет вовсе (ядро молчит,
        // подписки нет) — строки тоже нет: пустая строка «Прокси / Прокси» ничего
        // не даёт и занимает место.
        //
        // Строки «Подписки» здесь больше нет намеренно: она вела на вкладку, до
        // которой два сантиметра вниз, а название активной подписки и так стоит
        // в шапке экрана.
        state.servers.groups.getOrNull(state.servers.selected)?.let { group ->
            SelectorRow(
                label = group.name,
                value = group.now.ifBlank { stringResource(R.string.proxy) },
                leading = painterResource(R.drawable.ic_nav_servers),
                onClick = { onAction(MainAction.SelectTab(MainTab.Servers)) },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MainHeader(
    profileName: String?,
    logoPath: String?,
    updating: Boolean,
    onAction: (MainAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // clod: логотип провайдера (`profile-logo`) вместо значка приложения —
        // это его подписка, и в шапке уместнее его бренд. Читаем локальный
        // файл, а не адрес из заголовка: с чужого хоста картинка мигала бы на
        // холодном старте, не работала офлайн и отдавала бы ему адрес человека
        // при каждой отрисовке. Нет файла — остаётся значок приложения.
        val logo = rememberProviderLogo(logoPath)

        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp)),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_clash),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = profileName ?: stringResource(R.string.application_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SyncIconButton(
            spinning = updating,
            contentDescription = stringResource(R.string.clod_refresh_profile),
            onClick = { onAction(MainAction.UpdateAllProfiles) },
        )
    }
}

/**
 * Вкладка «Ещё». Собирает то, что на десктопе живёт в боковом меню, а у CMFA
 * лежало прямо на главном экране вперемешку с кнопкой подключения.
 */
/**
 * Часы сессии. Часы показываются, только когда они есть: «00:14:02» без часов
 * читается хуже, чем «14:02».
 */
private fun formatSession(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

/**
 * Баннер объявления и ссылки провайдера — то, что панель прислала заголовками
 * вместе с подпиской.
 *
 * Показывается только если панель что-то прислала: пустой блок на главном
 * экране занимал бы место у кнопки подключения ни за чем. Объявление серое
 * и не мигает акцентом — это информация, а не действие.
 */
@Composable
private fun PanelBanner(active: SubscriptionItem, onAction: (MainAction) -> Unit) {
    val panel = active.panel ?: return
    val notice = panel.announce.takeIf { it.isNotBlank() } ?: panel.promo
    val noticeUrl = if (panel.announce.isNotBlank()) panel.announceUrl else panel.promoUrl
    val reason = noServersReason(active.profile, panel)

    // Кнопок здесь больше нет: та же пара «Личный кабинет» и «Поддержка»
    // рисовалась и тут, и в карточке подписки, и сходились оба условия ровно
    // тогда, когда человек смотрит на экран внимательнее всего. Теперь пара
    // живёт в одном месте — рядом со сроком и трафиком, то есть там, где
    // человек и понял, что подписка кончается.
    if (notice.isBlank() && reason == null) return

    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        if (reason != null) {
            NoServersCard(
                reason = reason,
                panel = panel,
                profile = active.profile,
                onOpenUrl = { onAction(MainAction.OpenUrl(it)) },
                onOpenSettings = { onAction(MainAction.OpenAppSettings) },
            )

            Spacer(Modifier.height(8.dp))
        }

        if (notice.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .then(
                            if (noticeUrl.isNotBlank()) {
                                Modifier.clickable { onAction(MainAction.OpenUrl(noticeUrl)) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(14.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_outline_info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Подпись режима туннеля. Ключи строк те же, что у XML-слоя. */
@Composable
fun modeLabel(mode: TunnelState.Mode): String = stringResource(
    when (mode) {
        TunnelState.Mode.Direct -> R.string.direct_mode
        TunnelState.Mode.Global -> R.string.global_mode
        else -> R.string.rule_mode
    },
)

/**
 * Выбор режима туннеля. У CMFA он прятался в меню с тремя точками на экране
 * прокси; в макете это отдельный пункт в «Ещё», где его и ищут.
 *
 * Script в списке нет намеренно: ядро его больше не поддерживает, а показывать
 * пункт, который не применится, — врать пользователю.
 */
@Composable
private fun ModeRow(mode: TunnelState.Mode, locked: Boolean, onAction: (MainAction) -> Unit) {
    var picking by remember { mutableStateOf(false) }

    ActionRow(
        title = stringResource(R.string.clod_mode),
        // Замок виден строкой, а не только при нажатии: человек должен понимать,
        // почему выбор не открывается, ДО того как ткнул.
        subtitle = if (locked) {
            stringResource(R.string.clod_mode_locked, modeLabel(mode))
        } else {
            modeLabel(mode)
        },
        icon = painterResource(R.drawable.ic_baseline_vpn_lock),
        onClick = { picking = true },
    )

    if (picking && locked) {
        // Не список с выключенными строками, а объяснение: выбор, который
        // ничего не делает, читается как поломка приложения.
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.clod_mode)) },
            text = { Text(stringResource(R.string.clod_mode_locked_hint)) },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )

        return
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.clod_mode)) },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            text = {
                Column {
                    listOf(
                        TunnelState.Mode.Rule,
                        TunnelState.Mode.Global,
                        TunnelState.Mode.Direct,
                    ).forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picking = false
                                    onAction(MainAction.SetMode(candidate))
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = candidate == mode, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(modeLabel(candidate))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MoreTab(state: MainScreenState, onAction: (MainAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.clod_tab_more),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 18.dp, top = 20.dp, bottom = 12.dp),
            )
            SectionHeader(stringResource(R.string.clod_section_connection))
            ModeRow(
                mode = state.mode,
                // Провайдер вправе запретить смену режима (`clod-lock-mode`).
                locked = state.active?.panel?.lockMode == true,
                onAction = onAction,
            )
            ActionRow(
                title = stringResource(R.string.clod_apps),
                subtitle = stringResource(R.string.clod_apps_subtitle),
                icon = painterResource(R.drawable.ic_baseline_apps),
                onClick = { onAction(MainAction.OpenAccessControl) },
            )
            ActionRow(
                title = stringResource(R.string.network),
                subtitle = stringResource(R.string.clod_settings_network_subtitle),
                icon = painterResource(R.drawable.ic_baseline_dns),
                onClick = { onAction(MainAction.OpenNetworkSettings) },
            )
            ActionRow(
                title = stringResource(R.string.clod_geo_title),
                subtitle = stringResource(R.string.clod_geo_subtitle),
                icon = painterResource(R.drawable.ic_baseline_domain),
                onClick = { onAction(MainAction.OpenSubScreen(SubScreen.RoutingData)) },
            )
            if (state.hasProviders) {
                ActionRow(
                    title = stringResource(R.string.providers),
                    icon = painterResource(R.drawable.ic_baseline_swap_vertical_circle),
                    onClick = { onAction(MainAction.OpenProviders) },
                )
            }

            // Промежуточного экрана «Настройки» больше нет: он состоял из четырёх
            // строк и ничего к ним не добавлял. Сеть уехала к соединению — там её
            // и ищут, — остальные три лежат здесь.
            SectionHeader(stringResource(R.string.clod_section_settings))
            ActionRow(
                title = stringResource(R.string.app),
                subtitle = stringResource(R.string.clod_settings_app_subtitle),
                icon = painterResource(R.drawable.ic_baseline_settings),
                onClick = { onAction(MainAction.OpenAppSettings) },
            )
            ActionRow(
                title = stringResource(R.string.override),
                subtitle = stringResource(R.string.clod_settings_override_subtitle),
                icon = painterResource(R.drawable.ic_baseline_extension),
                onClick = { onAction(MainAction.OpenOverrideSettings) },
            )
            ActionRow(
                title = stringResource(R.string.meta_features),
                subtitle = stringResource(R.string.clod_settings_meta_subtitle),
                icon = painterResource(R.drawable.ic_baseline_meta),
                onClick = { onAction(MainAction.OpenMetaSettings) },
            )

            SectionHeader(stringResource(R.string.clod_section_support))

            // clod: кабинет и поддержка провайдера. На главном экране они живут
            // в карточке подписки, то есть видны в сессии и когда с подпиской
            // что-то не так; в спокойном состоянии карточки нет, и искать их
            // человек будет здесь — рядом с помощью и логами.
            state.active?.panel?.let { panel ->
                if (panel.portalUrl.isNotBlank()) {
                    ActionRow(
                        title = stringResource(R.string.clod_portal),
                        icon = painterResource(R.drawable.ic_baseline_account),
                        onClick = { onAction(MainAction.OpenUrl(panel.portalUrl)) },
                    )
                }
                if (panel.supportUrl.isNotBlank()) {
                    ActionRow(
                        title = stringResource(R.string.clod_support),
                        icon = painterResource(R.drawable.ic_baseline_chat),
                        onClick = { onAction(MainAction.OpenUrl(panel.supportUrl)) },
                    )
                }
            }

            ActionRow(
                title = stringResource(R.string.logs),
                icon = painterResource(R.drawable.ic_baseline_assignment),
                onClick = { onAction(MainAction.OpenLogs) },
            )
            ActionRow(
                title = stringResource(R.string.help),
                icon = painterResource(R.drawable.ic_baseline_help_center),
                onClick = { onAction(MainAction.OpenHelp) },
            )
            ActionRow(
                title = stringResource(R.string.clod_update_check),
                icon = painterResource(R.drawable.ic_baseline_update),
                onClick = { onAction(MainAction.CheckUpdate) },
            )
            ActionRow(
                title = stringResource(R.string.about),
                subtitle = state.about.versionName.takeIf { it.isNotBlank() },
                icon = painterResource(R.drawable.ic_baseline_info),
                onClick = { onAction(MainAction.OpenSubScreen(SubScreen.About)) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Логотип провайдера из локального файла.
 *
 * Декодируем один раз на путь: картинка меняется только вместе с обновлением
 * подписки, а перечитывать её на каждую перерисовку главного экрана — это
 * чтение с диска в композиции. Не прочиталась (файл удалили, формат не тот) —
 * возвращаем null, и шапка берёт значок приложения.
 */
@Composable
private fun rememberProviderLogo(path: String?): ImageBitmap? = remember(path) {
    if (path.isNullOrBlank()) return@remember null

    runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
}
