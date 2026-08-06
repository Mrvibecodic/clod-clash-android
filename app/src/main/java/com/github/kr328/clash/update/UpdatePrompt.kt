package com.github.kr328.clash.update

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.store.AppStore
import java.util.concurrent.TimeUnit

/**
 * Правила проверки обновлений. Без единой строчки интерфейса: окно рисует главный
 * экран на Compose, а решение «стоит ли вообще спрашивать» живёт здесь.
 *
 * Раньше здесь же были системные диалоги. Они показывали список изменений сырым
 * текстом — с решётками заголовков и дефисами пунктов, как он написан
 * в UPDATELOG.md, — и после ухода на системный экран за разрешением на установку
 * к обновлению было уже не вернуться.
 */
object UpdatePrompt {
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(24)

    /** Пора ли проверять само: раз в сутки и только если автопроверка включена. */
    fun shouldCheckInBackground(context: Context): Boolean {
        val store = AppStore(context)

        if (!store.autoCheckUpdate) return false

        return System.currentTimeMillis() - store.lastUpdateCheck >= CHECK_INTERVAL
    }

    /**
     * @param manual проверка по кнопке. От фоновой отличается тем, что не смотрит
     *   на пропущенную версию: человек нажал сам и ждёт ответа про текущее
     *   состояние, а не про своё решение недельной давности.
     */
    suspend fun check(context: Context, manual: Boolean, mixedPort: Int?): Updater.Available? {
        val store = AppStore(context)

        val available = runCatching {
            Updater.check(context, store.nightlyChannel, mixedPort)
        }.onFailure {
            Log.w("UpdatePrompt: проверка не удалась", it)
        }.getOrNull()

        store.lastUpdateCheck = System.currentTimeMillis()

        if (available == null) return null
        if (!manual && available.manifest.versionCode == store.skippedVersionCode) return null

        return available
    }

    fun skip(context: Context, versionCode: Long) {
        AppStore(context).skippedVersionCode = versionCode
    }
}
