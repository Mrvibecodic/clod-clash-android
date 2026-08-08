package com.github.kr328.clash.service.subscription

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.displayProfileName
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.readPanelInfo
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

private val stateSerializer = MapSerializer(String.serializer(), Long.serializer())

private const val ALERT_CHANNEL = "subscription_alert_channel"

/** Имя файла с состоянием напоминаний в каталоге профиля. */
private const val STATE_FILE = "alerts.json"

/**
 * Напоминания о сроке и трафике подписки.
 *
 * Зовётся в двух местах: после каждого обновления подписки (в том числе
 * неудачного — срок считается по системным часам и от сети не зависит) и при
 * открытии главного экрана. Второе обязательно: обновления расписаны
 * будильником, но интервал можно поставить в «вручную», и тогда будильника
 * нет вовсе — а подписка кончается всё равно.
 *
 * Своего таймера нет намеренно: он будил бы телефон ради работы, которую и так
 * делает открытие приложения, а сказать о конце подписки в тот момент, когда
 * человек на неё не смотрит, некому и незачем.
 */
suspend fun Context.reportSubscriptionAlerts(uuid: UUID) {
    if (!ServiceStore(this).enableSubNotifications) return

    // Уведомления запрещены системой (на Android 13+ разрешение можно не дать):
    // выходим ДО записи состояния. Иначе порог пометился бы пройденным, само
    // уведомление молча выбросил бы менеджер, и о нём не сказали бы уже никогда.
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

    val imported = ImportedDao().queryByUUID(uuid) ?: return

    // Панель молчит про пороги — берём умолчания; прислала пустой список —
    // значит напоминания выключены ею, и это надо уважать.
    val panel = readPanelInfo(uuid)
    val expireDays = panel?.notifyExpireDays ?: SubscriptionAlerts.DEFAULT_EXPIRE_DAYS
    val trafficPercent = panel?.notifyTrafficPercent ?: SubscriptionAlerts.DEFAULT_TRAFFIC_PERCENT

    if (expireDays.isEmpty() && trafficPercent.isEmpty()) {
        // Панель выключила обе семьи: старые отметки больше ни к чему
        // не относятся, и держать их в каталоге профиля незачем.
        stateFile(uuid).delete()

        return
    }

    val previous = readState(uuid)

    val outcome = SubscriptionAlerts.evaluate(
        snapshot = SubscriptionAlerts.Snapshot(
            expireAt = imported.expire,
            total = imported.total,
            used = imported.upload + imported.download,
            expireDays = expireDays,
            trafficPercent = trafficPercent,
            notified = previous,
        ),
        nowMillis = System.currentTimeMillis(),
    )

    if (outcome.notified != previous) {
        writeState(uuid, outcome.notified)
    }

    if (outcome.alerts.isEmpty()) return

    val name = displayProfileName(imported.uuid, imported.name)

    createAlertChannel()

    outcome.alerts.forEach { notifyAlert(it, name) }
}

/**
 * Состояние лежит файлом в каталоге профиля, а не одной строкой в настройках,
 * ровно по той же причине, что и `panel.json`: писать его могут одновременно
 * разные процессы (служба обновления в `:background` и приложение), и общая
 * строка на все подписки означала бы, что последний записавший затирает
 * чужие отметки.
 */
private fun Context.stateFile(uuid: UUID): File =
    importedDir.resolve(uuid.toString()).resolve(STATE_FILE)

private fun Context.readState(uuid: UUID): Map<String, Long> {
    val file = stateFile(uuid)

    if (!file.isFile) return emptyMap()

    return try {
        json.decodeFromString(stateSerializer, file.readText())
    } catch (e: Exception) {
        Log.w("Read $STATE_FILE of $uuid: $e", e)

        emptyMap()
    }
}

private fun Context.writeState(uuid: UUID, value: Map<String, Long>) {
    val file = stateFile(uuid)

    try {
        if (value.isEmpty()) {
            file.delete()
        } else {
            file.writeText(json.encodeToString(stateSerializer, value))
        }
    } catch (e: Exception) {
        Log.w("Write $STATE_FILE of $uuid: $e", e)
    }
}

private fun Context.createAlertChannel() {
    NotificationManagerCompat.from(this).createNotificationChannelsCompat(
        listOf(
            NotificationChannelCompat.Builder(
                ALERT_CHANNEL,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            ).setName(getString(R.string.clod_alert_channel)).build(),
        ),
    )
}

private fun Context.notifyAlert(alert: SubscriptionAlert, name: String) {
    // Идентификатор постоянный и свой у каждой семьи: свежий на каждое
    // напоминание копил бы их в шторке столбиком, а так новое сообщение
    // о сроке заменяет предыдущее.
    val id = when (alert) {
        is SubscriptionAlert.Expired, is SubscriptionAlert.ExpiresIn -> R.id.nf_subscription_expire
        is SubscriptionAlert.TrafficUsed -> R.id.nf_subscription_traffic
    }

    val title = when (alert) {
        is SubscriptionAlert.Expired -> getString(R.string.clod_alert_expired)
        is SubscriptionAlert.ExpiresIn -> resources.getQuantityString(
            R.plurals.clod_alert_expires_in,
            alert.days,
            alert.days,
        )

        is SubscriptionAlert.TrafficUsed -> getString(R.string.clod_alert_traffic, alert.percent)
    }

    // Ведём на главный экран, а не в свойства профиля: делать по такому
    // напоминанию нужно не «посмотреть настройки», а продлить или написать
    // в поддержку, и обе ссылки провайдера лежат на главной.
    val intent = PendingIntent.getActivity(
        this,
        id,
        Intent().setComponent(Components.MAIN_ACTIVITY),
        pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
    )

    val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
        .setColor(getColorCompat(R.color.color_clash))
        .setSmallIcon(R.drawable.ic_logo_service)
        .setContentTitle(title)
        .setContentText(name)
        .setContentIntent(intent)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(this).notify(id, notification)
}
