package com.github.kr328.clash

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.packageName
import com.github.kr328.clash.remote.StatusClient
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
                val running = withContext(Dispatchers.IO) {
                    StatusClient(context).currentProfile() != null
                }

                render(context, if (running) State.On else State.Off)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_WAIT -> render(context, State.Wait)
            Intents.ACTION_CLASH_STARTED -> render(context, State.On)
            Intents.ACTION_CLASH_STOPPED -> render(context, State.Off)
            else -> super.onReceive(context, intent)
        }
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
            R.id.widget_root,
            PendingIntent.getActivity(
                context,
                0,
                WidgetToggleActivity::class.intent
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
            ),
        )

        manager.updateAppWidget(component, views)
    }

    companion object {
        val ACTION_WIDGET_WAIT = "$packageName.action.WIDGET_WAIT"

        fun notifyWait(context: Context) {
            context.sendBroadcast(
                Intent(context, ToggleWidgetProvider::class.java)
                    .setAction(ACTION_WIDGET_WAIT)
            )
        }
    }
}
