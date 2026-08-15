package com.github.kr328.clash.design.compose.screen

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.SyncIcon
import com.github.kr328.clash.design.compose.component.SyncIconButton
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.service.model.Profile
import java.util.Date
import java.util.concurrent.TimeUnit

/** Состояние карточки подписки — то, чем определяется её цвет и текст бейджа. */
internal enum class SubscriptionState {
    Active,
    Expiring,
    Exhausted,
    Expired,
}

/**
 * Считает состояние подписки по данным профиля.
 *
 * `total` и `expire` приходят из заголовка `subscription-userinfo`, который панель
 * отдаёт вместе с конфигом; ноль в любом из них означает «ограничения нет», а не
 * «ноль осталось» — иначе безлимитная подписка показывалась бы исчерпанной.
 *
 * Порог «истекает» — трое суток, как на десктопе.
 */
internal fun subscriptionState(profile: Profile, now: Long): SubscriptionState {
    val used = profile.upload + profile.download
    return when {
        profile.expire in 1 until now -> SubscriptionState.Expired
        profile.total > 0 && used >= profile.total -> SubscriptionState.Exhausted
        profile.expire > 0 &&
            profile.expire - now <= TimeUnit.DAYS.toMillis(3) -> SubscriptionState.Expiring

        else -> SubscriptionState.Active
    }
}

@Composable
internal fun SubscriptionState.color(): Color = when (this) {
    SubscriptionState.Active -> ClodTheme.extraColors.statusConnected
    SubscriptionState.Expiring -> ClodTheme.extraColors.statusConnecting
    SubscriptionState.Exhausted, SubscriptionState.Expired -> MaterialTheme.colorScheme.error
}

@Composable
internal fun SubscriptionState.label(): String = stringResource(
    when (this) {
        SubscriptionState.Active -> R.string.clod_sub_active
        SubscriptionState.Expiring -> R.string.clod_sub_expiring
        SubscriptionState.Exhausted -> R.string.clod_sub_exhausted
        SubscriptionState.Expired -> R.string.clod_sub_expired
    },
)

/**
 * Вкладка «Подписки»: карточки со сроком и трафиком.
 *
 * Фильтров по группам, как в макете, здесь нет: группы подписок — понятие
 * десктопной версии, на Android профили плоские. Появятся вместе с группами.
 */
@Composable
fun SubscriptionsTab(state: SubscriptionsState, onAction: (MainAction) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.clod_tab_subscriptions),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // Кнопка не подменяется крутилкой, а крутится сама: подмена
            // прыгала вёрсткой и на глаз читалась как «кнопка пропала»,
            // а не как «идёт обновление».
            // Пока подписок нет, добавление — единственное, что здесь можно
            // сделать, и жить ему в углу незачем: угловой значок ищут глазами
            // на пустом экране дольше, чем читают подсказку по центру.
            if (state.profiles.isNotEmpty()) {
                SyncIconButton(
                    spinning = state.updating,
                    contentDescription = stringResource(R.string.clod_sub_update_all),
                    onClick = { onAction(MainAction.UpdateAllProfiles) },
                )
                IconButton(onClick = { onAction(MainAction.NewProfile) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_add),
                        contentDescription = stringResource(R.string.clod_sub_add),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (state.profiles.isEmpty()) {
            EmptySubscriptions(onAction)
            return@Column
        }

        // Чипы появляются, только когда группы кто-то завёл: одинокий чип
        // «Все · 1» — это строка, которая ничего не даёт и занимает место.
        val groups = state.profiles.mapNotNull { it.group }.distinct().sorted()
        if (groups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedGroup == null,
                    onClick = { onAction(MainAction.SelectSubscriptionGroup(null)) },
                    label = {
                        Text(
                            stringResource(R.string.clod_group_all) +
                                " · " + state.profiles.size,
                        )
                    },
                )
                groups.forEach { group ->
                    val count = state.profiles.count { it.group == group }
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { onAction(MainAction.SelectSubscriptionGroup(group)) },
                        label = { Text("$group · $count") },
                    )
                }
            }
        }

        val visible = state.profiles.filter {
            state.selectedGroup == null || it.group == state.selectedGroup
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = visible, key = { it.profile.uuid.toString() }) { item ->
                SubscriptionCard(
                    item = item,
                    known = groups,
                    updating = item.profile.uuid in state.updatingUuids,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    item: SubscriptionItem,
    known: List<String>,
    updating: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val profile = item.profile
    val context = LocalContext.current
    // Время берём на момент отрисовки: карточка перерисовывается при возврате на
    // вкладку и при любом обновлении списка, а секундной точности здесь не нужно.
    // С поправкой на часы панели: «осталось 3 дня» на сбитых часах телефона
    // иначе показывалось бы днём раньше или позже, чем на самом деле.
    val now = remember(profile) { System.currentTimeMillis() + item.panelClockSkew() }
    val status = subscriptionState(profile, now)
    val used = profile.upload + profile.download
    var menuOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    if (picking) {
        GroupPicker(
            current = item.group,
            known = known,
            onDismiss = { picking = false },
            onPick = {
                picking = false
                onAction(MainAction.SetSubscriptionGroup(profile, it))
            },
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            // Контейнерная роль вместо полупрозрачного primary: у роли контраст
            // с фоном посчитан схемой в обеих темах, а заливка 10 % на светлой
            // теме почти не отличалась от соседних карточек.
            containerColor = if (profile.active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable { onAction(MainAction.ActivateProfile(profile)) },
    ) {
        Row {
            // Отметка активной подписки. Заливки в 10 % не хватало: на светлой
            // теме она отличается от `surfaceContainerLow` на единицы яркости,
            // и при трёх карточках подряд было не видно, какая из них
            // применена. Место под полосу занято всегда — иначе текст карточек
            // прыгал бы вправо при каждом переключении подписки.
            Box(
                modifier = Modifier
                    .padding(vertical = 14.dp)
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(
                        if (profile.active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // Название от панели, а не «New Profile»: своё имя подписке
                        // человек в нашем сценарии добавления не задаёт вовсе.
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(status.label(), status.color())
                    // Пока подписка обновляется, у её карточки крутится значок:
                    // на «Обновить» жмут из меню, и после закрытия меню человеку
                    // больше негде увидеть, что запрос вообще ушёл.
                    //
                    // Место под значок занято всегда: появись он по месту, метка
                    // состояния и кнопка меню дёргались бы влево-вправо на каждом
                    // старте и финише обновления.
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(18.dp),
                    ) {
                        if (updating) {
                            SyncIcon(
                                spinning = true,
                                contentDescription = stringResource(R.string.clod_sub_updating),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_more_vert),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Обновление есть только у подписок по ссылке: локальный
                            // файл обновлять неоткуда.
                            if (profile.type != Profile.Type.File) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.update)) },
                                    // Пока предыдущее обновление не закончилось,
                                    // повторное нажатие только поставит в очередь
                                    // ещё один поход в сеть за тем же файлом.
                                    enabled = !updating,
                                    onClick = {
                                        menuOpen = false
                                        onAction(MainAction.UpdateProfile(profile))
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.edit)) },
                                onClick = {
                                    menuOpen = false
                                    onAction(MainAction.EditProfile(profile))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clod_group_move)) },
                                onClick = {
                                    menuOpen = false
                                    picking = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                onClick = {
                                    menuOpen = false
                                    onAction(MainAction.DeleteProfile(profile))
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (profile.total > 0) {
                            "${Formatter.formatShortFileSize(context, used)} / " +
                                Formatter.formatShortFileSize(context, profile.total)
                        } else {
                            Formatter.formatShortFileSize(context, used) + " · " +
                                stringResource(R.string.clod_sub_unlimited)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (profile.expire > 0) {
                        val days = ((profile.expire - now) / TimeUnit.DAYS.toMillis(1)).toInt()
                        Text(
                            text = if (days >= 0) {
                                stringResource(R.string.clod_sub_days, days)
                            } else {
                                stringResource(
                                    R.string.clod_sub_until,
                                    DateFormat.getDateFormat(context).format(Date(profile.expire)),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = status.color(),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                if (profile.total > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / profile.total).coerceIn(0f, 1f) },
                        color = status.color(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                    )
                }
            }
        }
    }
}

/**
 * Карточка проблемной подписки на главном экране: срок, трафик и, если панель
 * их прислала, кнопки продления. Отдельная от карточки в списке — там нужны
 * меню и выбор, а здесь только сведения и действие.
 *
 * Показывается ТОЛЬКО когда с подпиской что-то не так. Здоровое состояние
 * теперь целиком закрыто без неё: строка в шапке отвечает «жива ли подписка»,
 * а в отключённом состоянии срок и трафик рисуют карточки квоты — и большая
 * карточка при живой подписке лишь дублировала бы их.
 */
@Composable
fun ActiveSubscriptionCard(
    item: SubscriptionItem,
    showActions: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val profile = item.profile
    val now = remember(profile) { System.currentTimeMillis() + item.panelClockSkew() }
    val status = subscriptionState(profile, now)
    val critical = status != SubscriptionState.Active

    // Проблемы нет — карточке на главном делать нечего.
    if (!critical) return
    if (profile.total <= 0L && profile.expire <= 0L) return

    val context = LocalContext.current
    val used = profile.upload + profile.download
    val panel = item.panel

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (profile.expire > 0) {
                    val days = ((profile.expire - now) / TimeUnit.DAYS.toMillis(1)).toInt()
                    StatusBadge(
                        text = if (days >= 0) {
                            stringResource(R.string.clod_sub_days, days)
                        } else {
                            status.label()
                        },
                        color = status.color(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (profile.total > 0) {
                        "${Formatter.formatShortFileSize(context, used)} / " +
                            Formatter.formatShortFileSize(context, profile.total)
                    } else {
                        Formatter.formatShortFileSize(context, used) + " · " +
                            stringResource(R.string.clod_sub_unlimited)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (profile.expire > 0) {
                    Text(
                        text = stringResource(
                            R.string.clod_sub_until,
                            DateFormat.getDateFormat(context).format(Date(profile.expire)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (profile.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (used.toFloat() / profile.total).coerceIn(0f, 1f) },
                    color = status.color(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                )
            }

            // clod: платёжных кнопок нет — единственная ссылка провайдера,
            // ведущая к оплате, это личный кабинет. Место у пары одно на весь
            // экран: раньше она же рисовалась баннером выше, и в критическом
            // состоянии человек видел две одинаковые пары подряд.
            if (showActions && panel != null &&
                (panel.portalUrl.isNotBlank() || panel.supportUrl.isNotBlank())
            ) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (panel.portalUrl.isNotBlank()) {
                        Button(
                            onClick = { onAction(MainAction.OpenUrl(panel.portalUrl)) },
                            // Долю ширины забирает только «Личный кабинет»:
                            // при делении поровну он не влезал в половину
                            // экрана, переносился на две строки и тянул вверх
                            // соседнюю кнопку. «Поддержка» короче и занимает
                            // ровно столько, сколько ей надо.
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.clod_portal),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (panel.supportUrl.isNotBlank()) {
                        OutlinedButton(
                            onClick = { onAction(MainAction.OpenUrl(panel.supportUrl)) },
                            // Ширину делит только соседняя кнопка. Но если её
                            // нет вовсе, одинокая «Поддержка» не должна липнуть
                            // к левому краю — тогда долю забирает она.
                            modifier = if (panel.portalUrl.isBlank()) {
                                Modifier.weight(1f)
                            } else {
                                Modifier
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.clod_support),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Выбор группы для подписки: уже заведённые группы списком плюс поле для новой.
 * Отдельного экрана управления группами нет намеренно — группа это одна строка
 * текста, и заводить ради неё раздел настроек не за что.
 */
@Composable
private fun GroupPicker(
    current: String?,
    known: List<String>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var fresh by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clod_group_move)) },
        confirmButton = {
            TextButton(
                onClick = { onPick(fresh) },
                enabled = fresh.isNotBlank(),
            ) {
                Text(stringResource(R.string.clod_group_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        text = {
            Column {
                GroupOption(
                    label = stringResource(R.string.clod_group_none),
                    selected = current == null,
                    onClick = { onPick(null) },
                )
                known.forEach { group ->
                    GroupOption(
                        label = group,
                        selected = group == current,
                        onClick = { onPick(group) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = fresh,
                    onValueChange = { fresh = it },
                    label = { Text(stringResource(R.string.clod_group_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun GroupOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
internal fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptySubscriptions(onAction: (MainAction) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.clod_no_subscriptions),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.clod_no_subscriptions_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            // Кнопка живёт здесь только в пустом состоянии: как только
            // появится первая подписка, добавление уезжает обратно в угол,
            // чтобы не отнимать место у списка.
            Button(onClick = { onAction(MainAction.NewProfile) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_add),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clod_sub_add))
            }
        }
    }
}
