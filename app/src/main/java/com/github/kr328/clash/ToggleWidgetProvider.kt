package com.github.kr328.clash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.packageName
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.R as ServiceR
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToggleWidgetProvider : AppWidgetProvider() {
    private enum class State {
        Off, Wait, On
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()

        Global.launch {
            try {
                render(context, if (isRunning(context)) State.On else State.Off)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_TOGGLE -> {
                val pending = goAsync()

                Global.launch {
                    try {
                        toggle(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_WIDGET_WAIT -> render(context, State.Wait)
            Intents.ACTION_CLASH_STARTED -> render(context, State.On)
            Intents.ACTION_CLASH_STOPPED -> render(context, State.Off)
            else -> super.onReceive(context, intent)
        }
    }

    private suspend fun toggle(context: Context) {
        if (isRunning(context)) {
            render(context, State.Wait)

            context.stopClashService()

            showToast(context, DesignR.string.external_control_stopped)

            return
        }

        val vpnRequest = context.startClashService()

        if (vpnRequest == null) {
            render(context, State.Wait)

            showToast(context, DesignR.string.external_control_started)
        } else {
            requestVpnPermission(context)
        }
    }

    private suspend fun isRunning(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            StatusClient(context).isRunning()
        }
    }

    private fun showToast(context: Context, text: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestVpnPermission(context: Context) {
        val manager = NotificationManagerCompat.from(context)

        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                PERMISSION_CHANNEL,
                NotificationManagerCompat.IMPORTANCE_HIGH
            ).setName(context.getString(DesignR.string.clod_widget_channel)).build()
        )

        val notification = NotificationCompat.Builder(context, PERMISSION_CHANNEL)
            .setSmallIcon(ServiceR.drawable.ic_logo_service)
            .setContentTitle(context.getString(DesignR.string.clod_widget_perm_title))
            .setContentText(context.getString(DesignR.string.clod_widget_perm_text))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    WidgetToggleActivity::class.intent
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )
            .build()

        runCatching { manager.notify(PERMISSION_NOTIFICATION_ID, notification) }
    }

    private fun render(context: Context, state: State) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ToggleWidgetProvider::class.java)

        if (manager.getAppWidgetIds(component).isEmpty())
            return

        val views = RemoteViews(context.packageName, R.layout.widget_toggle)

        views.setImageViewResource(
            R.id.widget_circle,
            when (state) {
                State.Off -> R.drawable.widget_circle_dim
                State.Wait -> R.drawable.widget_circle_plain
                State.On -> R.drawable.widget_circle_glow
            },
        )

        views.setImageViewResource(
            R.id.widget_dot,
            when (state) {
                State.Off -> R.drawable.widget_dot_off
                State.Wait -> R.drawable.widget_dot_wait
                State.On -> R.drawable.widget_dot_on
            },
        )

        views.setOnClickPendingIntent(
            R.id.widget_button,
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, ToggleWidgetProvider::class.java)
                    .setAction(ACTION_WIDGET_TOGGLE),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
            ),
        )

        manager.updateAppWidget(component, views)
    }

    companion object {
        val ACTION_WIDGET_TOGGLE = "$packageName.action.WIDGET_TOGGLE"
        val ACTION_WIDGET_WAIT = "$packageName.action.WIDGET_WAIT"

        private const val PERMISSION_CHANNEL = "widget_permission_channel"
        private const val PERMISSION_NOTIFICATION_ID = 0x7701

        fun notifyWait(context: Context) {
            context.sendBroadcast(
                Intent(context, ToggleWidgetProvider::class.java)
                    .setAction(ACTION_WIDGET_WAIT)
            )
        }
    }
}
