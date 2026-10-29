package com.github.kr328.clash.design.compose.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.core.util.toBytesString
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.component.ConfirmDialog
import com.github.kr328.clash.design.compose.component.Pill
import com.github.kr328.clash.design.compose.component.RadioOption
import com.github.kr328.clash.design.compose.component.SyncIcon
import com.github.kr328.clash.design.compose.component.SyncIconButton
import com.github.kr328.clash.design.compose.component.TrafficLimit
import com.github.kr328.clash.design.compose.component.trafficLimit
import com.github.kr328.clash.design.compose.component.usedTraffic
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.design.compose.theme.statusContainer
import com.github.kr328.clash.design.compose.theme.statusText
import com.github.kr328.clash.design.util.bidiIsolated
import com.github.kr328.clash.design.util.formatDate
import com.github.kr328.clash.design.util.localeCollator
import com.github.kr328.clash.design.util.relativeTime
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.subscription.wholeDaysLeft
import java.util.concurrent.TimeUnit

internal enum class SubscriptionState {
    Draft,
    Active,
    Expiring,
    Exhausted,
    Expired,
}

internal fun subscriptionState(profile: Profile, now: Long): SubscriptionState {
    val used = profile.usedTraffic()
    return when {
        !profile.imported -> SubscriptionState.Draft
        profile.expire in 1 until now -> SubscriptionState.Expired
        profile.total > 0 && used >= profile.total -> SubscriptionState.Exhausted
        profile.expire > 0 &&
            profile.expire - now <= TimeUnit.DAYS.toMillis(3) -> SubscriptionState.Expiring

        else -> SubscriptionState.Active
    }
}

@Composable
internal fun SubscriptionState.color(): Color = when (this) {
    SubscriptionState.Draft -> ClodTheme.extraColors.statusStopped
    SubscriptionState.Active -> ClodTheme.extraColors.statusConnected
    SubscriptionState.Expiring -> ClodTheme.extraColors.statusConnecting
    SubscriptionState.Exhausted, SubscriptionState.Expired -> MaterialTheme.colorScheme.error
}

@Composable
internal fun SubscriptionState.label(): String = stringResource(
    when (this) {
        SubscriptionState.Draft -> R.string.clod_sub_draft
        SubscriptionState.Active -> R.string.clod_sub_active
        SubscriptionState.Expiring -> R.string.clod_sub_expiring
        SubscriptionState.Exhausted -> R.string.clod_sub_exhausted
        SubscriptionState.Expired -> R.string.clod_sub_expired
    },
)

@Composable
internal fun expiryLeft(expire: Long, now: Long): String? {
    if (expire <= 0 || expire <= now) return null

    val days = wholeDaysLeft(expire - now)

    return if (days > 0) {
        stringResource(R.string.clod_sub_days, days)
    } else {
        stringResource(R.string.clod_sub_last_day)
    }
}

@Composable
internal fun expiryDate(expire: Long, now: Long): String = stringResource(
    if (expire in 1 until now) R.string.clod_sub_expired_at else R.string.clod_sub_until,
    formatDate(expire),
)

@Composable
fun SubscriptionsTab(state: SubscriptionsState, onAction: (MainAction) -> Unit, loaded: Boolean) {
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
            // До первого чтения «подписок нет» — неправда: данные ещё не пришли
            if (loaded) EmptySubscriptions(onAction) else Waiting()
            return@Column
        }

        val counts = remember(state.profiles) {
            state.profiles.mapNotNull { it.group }.groupingBy { it }.eachCount()
        }
        val groups = remember(counts) {
            counts.keys.sortedWith(localeCollator())
        }
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
                    val count = counts[group] ?: 0
                    FilterChip(
                        selected = state.selectedGroup == group,
                        onClick = { onAction(MainAction.SelectSubscriptionGroup(group)) },
                        label = { Text("$group · $count") },
                    )
                }
            }
        }

        val visible = remember(state.profiles, state.selectedGroup) {
            state.profiles.filter {
                state.selectedGroup == null || it.group == state.selectedGroup
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
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
    val clock = LocalClock.current
    val now = remember(profile, clock) { item.panelNow() }
    val status = subscriptionState(profile, now)
    val used = profile.usedTraffic()
    val missed = remember(profile, clock) { item.missedUpdates(clock) }
    val warn = ClodTheme.extraColors.statusConnecting
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(R.string.clod_sub_delete_message, item.title.bidiIsolated()),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                deleting = false
                onAction(MainAction.DeleteProfile(profile))
            },
            onDismiss = { deleting = false },
        )
    }

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
            containerColor = if (profile.active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = if (missed >= 2) BorderStroke(2.dp, warn) else null,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = profile.active, role = Role.RadioButton) {
                onAction(MainAction.ActivateProfile(profile))
            },
    ) {
        Row {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title.bidiIsolated(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(
                        text = status.label(),
                        color = status.color(),
                        on = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
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
                                contentDescription = stringResource(R.string.clod_sub_menu),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (item.updatable) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.update)) },
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
                                    deleting = true
                                },
                            )
                        }
                    }
                }

                if (profile.active) {
                    Text(
                        text = stringResource(R.string.clod_sub_in_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                UsageBlock(
                    profile = profile,
                    used = used,
                    expiry = if (profile.expire > 0) {
                        expiryLeft(profile.expire, now) ?: expiryDate(profile.expire, now)
                    } else {
                        null
                    },
                    expiryStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = status.color().statusText(),
                        fontWeight = FontWeight.Medium,
                    ),
                    gap = 6.dp,
                    trackColor = if (profile.active) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    } else {
                        ProgressIndicatorDefaults.linearTrackColor
                    },
                )

                if (missed == 1) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(warn),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.clod_sub_stale, relativeTime(profile.fetchedAt, clock)),
                            style = MaterialTheme.typography.bodySmall,
                            color = warn.statusText(),
                        )
                    }
                } else if (missed >= 2) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = warn.copy(alpha = 0.3f))
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.clod_sub_stale_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = warn.statusText(),
                            )
                            Text(
                                text = stringResource(R.string.clod_sub_stale_detail, relativeTime(profile.fetchedAt, clock)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onAction(MainAction.UpdateProfile(profile)) },
                            enabled = !updating,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = warn,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Text(stringResource(R.string.update))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveSubscriptionCard(
    item: SubscriptionItem,
    showActions: Boolean,
    onAction: (MainAction) -> Unit,
) {
    val profile = item.profile
    val clock = LocalClock.current
    val now = remember(profile, clock) { item.panelNow() }
    val status = subscriptionState(profile, now)
    val critical = status != SubscriptionState.Active

    if (!critical) return
    if (profile.total <= 0L && profile.expire <= 0L) return

    val used = profile.usedTraffic()
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
                    text = item.title.bidiIsolated(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (profile.expire > 0) {
                    StatusBadge(
                        text = expiryLeft(profile.expire, now) ?: status.label(),
                        color = status.color(),
                        on = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                }
            }

            // Карточка есть только при лимите трафика или сроке, так строка
            // «трафик · срок» здесь не пропадает никогда
            UsageBlock(
                profile = profile,
                used = used,
                expiry = if (profile.expire > 0) expiryDate(profile.expire, now) else null,
                expiryStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                gap = 8.dp,
            )

            if (showActions && panel != null &&
                (panel.portalUrl.isNotBlank() || panel.supportUrl.isNotBlank())
            ) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (panel.portalUrl.isNotBlank()) {
                        Button(
                            onClick = { onAction(MainAction.OpenUrl(panel.portalUrl)) },
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

@Composable
private fun UsageBlock(
    profile: Profile,
    used: Long,
    expiry: String?,
    expiryStyle: TextStyle,
    gap: Dp,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
) {
    val traffic = trafficText(profile, used)

    if (traffic != null || expiry != null) {
        Spacer(Modifier.height(gap))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (traffic != null) {
                Text(
                    text = traffic,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (expiry != null) {
                Text(text = expiry, style = expiryStyle)
            }
        }
    }

    if (profile.total > 0) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (used.toFloat() / profile.total).coerceIn(0f, 1f) },
            color = MaterialTheme.colorScheme.primary,
            trackColor = trackColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
        )
    }
}

@Composable
internal fun trafficText(profile: Profile, used: Long): String? = when (val limit = profile.trafficLimit()) {
    is TrafficLimit.Limited -> used.toBytesString() + " / " + limit.total.toBytesString()
    TrafficLimit.Unlimited -> used.toBytesString() + " · " + stringResource(R.string.clod_sub_unlimited)
    TrafficLimit.Unknown -> if (used > 0) used.toBytesString() else null
}

@Composable
private fun GroupPicker(
    current: String?,
    known: List<String>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var fresh by rememberSaveable { mutableStateOf("") }

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
                Text(stringResource(R.string.cancel))
            }
        },
        text = {
            Column(Modifier.selectableGroup()) {
                RadioOption(
                    label = stringResource(R.string.clod_group_none),
                    selected = current == null,
                    onClick = { onPick(null) },
                )
                known.forEach { group ->
                    RadioOption(
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
internal fun StatusBadge(text: String, color: Color, on: Color) {
    Pill(
        container = color.statusContainer(on),
        contentColor = color.statusText(),
        horizontal = 8.dp,
        vertical = 3.dp,
        fontWeight = FontWeight.Medium,
    ) {
        Text(text)
    }
}

@Composable
private fun EmptySubscriptions(onAction: (MainAction) -> Unit) {
    EmptyState(
        title = stringResource(R.string.clod_no_subscriptions),
        hint = stringResource(R.string.clod_no_subscriptions_hint),
    ) {
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
