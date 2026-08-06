package com.github.kr328.clash.design.compose.screen

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.service.model.Profile
import java.util.Date
import java.util.concurrent.TimeUnit

/** Состояние карточки подписки — то, чем определяется её цвет и текст бейджа. */
private enum class SubscriptionState {
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
private fun subscriptionState(profile: Profile, now: Long): SubscriptionState {
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
private fun SubscriptionState.color(): Color = when (this) {
    SubscriptionState.Active -> ClodTheme.extraColors.statusConnected
    SubscriptionState.Expiring -> ClodTheme.extraColors.statusConnecting
    SubscriptionState.Exhausted, SubscriptionState.Expired -> MaterialTheme.colorScheme.error
}

@Composable
private fun SubscriptionState.label(): String = stringResource(
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
            if (state.updating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else if (state.profiles.isNotEmpty()) {
                IconButton(onClick = { onAction(MainAction.UpdateAllProfiles) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_sync),
                        contentDescription = stringResource(R.string.clod_sub_update_all),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onAction(MainAction.NewProfile) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_add),
                    contentDescription = stringResource(R.string.clod_sub_add),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (state.profiles.isEmpty()) {
            EmptySubscriptions()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = state.profiles, key = { it.profile.uuid.toString() }) { item ->
                SubscriptionCard(item, onAction)
            }
        }
    }
}

@Composable
private fun SubscriptionCard(item: SubscriptionItem, onAction: (MainAction) -> Unit) {
    val profile = item.profile
    val context = LocalContext.current
    // Время берём на момент отрисовки: карточка перерисовывается при возврате на
    // вкладку и при любом обновлении списка, а секундной точности здесь не нужно.
    val now = remember(profile) { System.currentTimeMillis() }
    val status = subscriptionState(profile, now)
    val used = profile.upload + profile.download
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (profile.active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAction(MainAction.ActivateProfile(profile)) },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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

@Composable
private fun StatusBadge(text: String, color: Color) {
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
private fun EmptySubscriptions() {
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
        }
    }
}
