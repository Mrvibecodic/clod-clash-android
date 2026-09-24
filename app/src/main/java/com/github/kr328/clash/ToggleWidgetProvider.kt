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
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.packageName
import com.github.kr328.clash.util.withAppLocale
import com.github.kr328.clash.design.R as DesignR
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
                val status = withContext(Dispatchers.IO) { StatusClient(context).status() }

                render(
                    context,
                    when {
                        status.running -> State.On
                        status.starting -> State.Wait
                        else -> State.Off
                    },
                )
            } catch (e: Exception) {
                Log.w("Widget render: $e", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WIDGET_WAIT -> render(context, State.Wait)
            Intents.ACTION_CLASH_STARTING -> render(context, State.Wait)
            Intents.ACTION_CLASH_STARTED -> render(context, State.On)
            Intents.ACTION_CLASH_STOPPED -> render(context, State.Off)
            LEGACY_ACTION_TOGGLE -> {
                context.startActivity(
                    WidgetToggleActivity::class.intent
                        .setAction(Intents.ACTION_TOGGLE_CLASH)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )

                onUpdate(context, AppWidgetManager.getInstance(context) ?: return, IntArray(0))
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        val ACTION_WIDGET_WAIT = "$packageName.action.WIDGET_WAIT"
        private val LEGACY_ACTION_TOGGLE = "$packageName.action.WIDGET_TOGGLE"

        fun notifyWait(context: Context) {
            context.sendBroadcast(
                Intent(context, ToggleWidgetProvider::class.java)
                    .setAction(ACTION_WIDGET_WAIT)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        }

        fun renderRecreated(context: Context) {
            runCatching { render(context, State.Off) }.onFailure { Log.w("Widget render: $it", it) }
        }

        private fun render(context: Context, state: State) {
            val manager = AppWidgetManager.getInstance(context) ?: return
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

            views.setContentDescription(
                R.id.widget_button,
                context.withAppLocale().getString(
                    when (state) {
                        State.Off -> DesignR.string.shortcut_start_long
                        State.Wait -> R.string.launch_name
                        State.On -> DesignR.string.shortcut_stop_long
                    },
                ),
            )

            views.setOnClickPendingIntent(
                R.id.widget_button,
                PendingIntent.getActivity(
                    context,
                    R.id.widget_button,
                    WidgetToggleActivity::class.intent
                        .setAction(Intents.ACTION_TOGGLE_CLASH)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                ),
            )

            manager.updateAppWidget(component, views)
        }
    }
}
