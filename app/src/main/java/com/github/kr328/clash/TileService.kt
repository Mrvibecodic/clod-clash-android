package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.service.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class TileService : TileService() {
    private var currentProfile = ""
    private var clashRunning = false

    /**
     * Имя текущей подписки живёт в процессе службы (`:background`) и достаётся
     * оттуда через `ContentProvider`, то есть блокирующим вызовом Binder,
     * который заодно может этот процесс поднять.
     *
     * Шторка быстрых настроек рисуется системным процессом и ждёт наш ответ:
     * замороженная или занятая служба означала бы подвисшую шторку и ANR.
     * Поэтому спрашиваем с `Dispatchers.IO`, а плитку рисуем дважды — сразу
     * тем, что известно, и ещё раз, когда придёт ответ.
     */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var refreshing: Job? = null

    override fun onClick() {
        val tile = qsTile ?: return

        when (tile.state) {
            Tile.STATE_INACTIVE -> {
                startClashService()
            }
            Tile.STATE_ACTIVE -> {
                stopClashService()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()

        registerReceiverCompat(
            receiver,
            IntentFilter().apply {
                addAction(Intents.ACTION_CLASH_STARTED)
                addAction(Intents.ACTION_CLASH_STOPPED)
                addAction(Intents.ACTION_PROFILE_LOADED)
                addAction(Intents.ACTION_SERVICE_RECREATED)
            },
            Permissions.RECEIVE_SELF_BROADCASTS,
            null
        )

        updateTile()

        refreshProfile(updateRunning = true)
    }

    override fun onStopListening() {
        super.onStopListening()

        refreshing?.cancel()

        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        scope.cancel()

        super.onDestroy()
    }

    /**
     * Спрашивает имя подписки у службы и перерисовывает плитку.
     *
     * [updateRunning] — считать ли по ответу, запущено ли ядро. При старте
     * прослушивания другого источника нет; на `ACTION_PROFILE_LOADED` факт
     * запуска уже известен из самого объявления, и перебивать его ответом
     * не нужно.
     */
    private fun refreshProfile(updateRunning: Boolean) {
        refreshing?.cancel()

        refreshing = scope.launch {
            val name = withContext(Dispatchers.IO) {
                StatusClient(this@TileService).currentProfile()
            }

            if (updateRunning) {
                clashRunning = name != null
            }

            currentProfile = name ?: ""

            updateTile()
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return

        tile.state = if (clashRunning)
            Tile.STATE_ACTIVE
        else
            Tile.STATE_INACTIVE

        tile.label = if (currentProfile.isEmpty())
            getText(R.string.launch_name)
        else
            currentProfile

        tile.icon = Icon.createWithResource(this, R.drawable.ic_logo_service)

        tile.updateTile()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true

                    currentProfile = ""
                }
                Intents.ACTION_CLASH_STOPPED, Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false

                    currentProfile = ""

                    refreshing?.cancel()
                }
                Intents.ACTION_PROFILE_LOADED -> {
                    refreshProfile(updateRunning = false)
                }
            }

            updateTile()
        }
    }
}
