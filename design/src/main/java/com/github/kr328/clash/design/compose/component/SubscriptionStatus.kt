package com.github.kr328.clash.design.compose.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.compose.theme.ClodTheme
import com.github.kr328.clash.service.model.PanelInfo
import com.github.kr328.clash.service.model.Profile
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Почему подключаться не к чему.
 *
 * Сервис подписки, когда выдавать нечего, не отвечает ошибкой: он отвечает
 * успехом и кладёт вместо серверов узлы-обманки, которые ядро уже выбросило
 * фильтром. Пустой список без объяснения читается как поломка приложения,
 * поэтому причину нужно назвать — и назвать ту, которая есть на самом деле.
 *
 * Порядок проверок не случаен: отказ по устройству перекрывает всё остальное,
 * потому что в этом случае срок и трафик могут быть в полном порядке.
 */
enum class NoServersReason {
    /** Устройств больше, чем разрешено подпиской. */
    DeviceLimit,

    /** Подписка требует опознания устройства, а оно выключено. */
    DeviceNotIdentified,

    /** Срок подписки закончился. */
    Expired,

    /** Трафик израсходован полностью. */
    Traffic,

    /**
     * Срок и трафик в порядке, а серверов нет. Отключённую подписку
     * и ненастроенные серверы по её данным не различить, и врать про причину
     * нельзя — говорим ровно то, что знаем.
     */
    Provider,
}

private const val HWID_LIMIT_REACHED = "limit"
private const val HWID_NOT_SUPPORTED = "not-supported"

/**
 * Причина или `null`, если серверы выданы и всё в порядке.
 *
 * @param now системное время; передаётся снаружи, чтобы карточка и таймеры
 *   на одном экране отвечали на один вопрос одинаково.
 */
fun noServersReason(profile: Profile?, panel: PanelInfo?, now: Long = System.currentTimeMillis()): NoServersReason? {
    when (panel?.hwidState) {
        HWID_LIMIT_REACHED -> return NoServersReason.DeviceLimit
        HWID_NOT_SUPPORTED -> return NoServersReason.DeviceNotIdentified
    }

    if (panel?.noServers != true) return null

    if (profile != null) {
        if (profile.expire in 1 until now) return NoServersReason.Expired

        val used = profile.upload + profile.download

        if (profile.total > 0 && used >= profile.total) return NoServersReason.Traffic
    }

    return NoServersReason.Provider
}

/**
 * Карточка «подключаться не к чему»: что случилось и что с этим делать.
 *
 * Кнопки берутся из того, что прислал владелец подписки: «Продлить» —
 * из `clod-renew-url`, «Докупить» — из `clod-topup-url`, «Поддержка» —
 * из `support-url`. Нет адреса — нет кнопки: кнопка, ведущая в никуда,
 * хуже её отсутствия.
 */
@Composable
fun NoServersCard(
    reason: NoServersReason,
    panel: PanelInfo?,
    profile: Profile?,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val warning = reason == NoServersReason.Traffic

    val accent = if (warning) ClodTheme.extraColors.statusConnecting else MaterialTheme.colorScheme.error

    Card(
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.13f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconOf(reason)),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(titleOf(reason)),
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = messageOf(reason, panel, profile),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Actions(
                reason = reason,
                panel = panel,
                onOpenUrl = onOpenUrl,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun Actions(
    reason: NoServersReason,
    panel: PanelInfo?,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val renew = panel?.renewUrl.orEmpty()
    val topup = panel?.topupUrl.orEmpty()
    val support = panel?.supportUrl.orEmpty()

    // Главное действие своё у каждой причины: продлить истёкшую, докупить
    // кончившийся трафик, включить опознание. Там, где сделать нечего,
    // главным становится обращение в поддержку.
    val primary: Pair<Int, () -> Unit>? = when {
        reason == NoServersReason.DeviceNotIdentified -> R.string.clod_open_settings to onOpenSettings
        reason == NoServersReason.Expired && renew.isNotBlank() -> R.string.clod_renew to { onOpenUrl(renew) }
        reason == NoServersReason.Traffic && topup.isNotBlank() -> R.string.clod_topup to { onOpenUrl(topup) }
        support.isNotBlank() -> R.string.clod_support to { onOpenUrl(support) }
        else -> null
    }

    val secondary = support
        .takeIf { it.isNotBlank() && primary?.first != R.string.clod_support }
        ?.let { R.string.clod_support to { onOpenUrl(it) } }

    if (primary == null && secondary == null) return

    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        primary?.let { (label, action) ->
            Button(
                onClick = action,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(label))
            }
        }

        secondary?.let { (label, action) ->
            OutlinedButton(onClick = action) {
                Text(stringResource(label))
            }
        }
    }
}

private fun iconOf(reason: NoServersReason): Int = when (reason) {
    NoServersReason.DeviceLimit, NoServersReason.DeviceNotIdentified -> R.drawable.ic_baseline_key
    NoServersReason.Expired -> R.drawable.ic_baseline_update
    NoServersReason.Traffic -> R.drawable.ic_baseline_swap_vert
    NoServersReason.Provider -> R.drawable.ic_outline_info
}

private fun titleOf(reason: NoServersReason): Int = when (reason) {
    NoServersReason.DeviceLimit -> R.string.clod_no_servers_device_title
    NoServersReason.DeviceNotIdentified -> R.string.clod_no_servers_unidentified_title
    NoServersReason.Expired -> R.string.clod_no_servers_expired_title
    NoServersReason.Traffic -> R.string.clod_no_servers_traffic_title
    NoServersReason.Provider -> R.string.clod_no_servers_provider_title
}

@Composable
private fun messageOf(reason: NoServersReason, panel: PanelInfo?, profile: Profile?): String = when (reason) {
    NoServersReason.DeviceLimit -> if ((panel?.hwidMaxDevices ?: 0) > 0) {
        stringResource(R.string.clod_no_servers_device_count, panel!!.hwidMaxDevices)
    } else {
        // Сколько устройств разрешено — не сказали; выдумывать число нельзя.
        stringResource(R.string.clod_no_servers_device)
    }

    NoServersReason.DeviceNotIdentified -> stringResource(R.string.clod_no_servers_unidentified)

    NoServersReason.Expired -> {
        val expire = profile?.expire ?: 0

        if (expire > 0) {
            stringResource(R.string.clod_no_servers_expired_date, formatDate(expire))
        } else {
            stringResource(R.string.clod_no_servers_expired)
        }
    }

    NoServersReason.Traffic -> {
        val refill = refillMillis(panel)

        if (refill > 0) {
            stringResource(R.string.clod_no_servers_traffic_date, formatDate(refill))
        } else {
            stringResource(R.string.clod_no_servers_traffic)
        }
    }

    NoServersReason.Provider -> stringResource(R.string.clod_no_servers_provider)
}

/** Дата обновления трафика приходит в секундах — экран считает в миллисекундах. */
private fun refillMillis(panel: PanelInfo?): Long {
    val seconds = panel?.refillDate ?: 0

    return if (seconds > 0) TimeUnit.SECONDS.toMillis(seconds) else 0
}

@Composable
private fun formatDate(millis: Long): String {
    return DateFormat.getDateInstance(DateFormat.LONG).format(Date(millis))
}
