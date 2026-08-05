package com.github.kr328.clash.update

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.widget.Toast
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.store.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Связка «проверить — спросить — скачать — поставить».
 *
 * Диалоги здесь намеренно системные и минимальные: интерфейс приложения переезжает
 * на Compose отдельным этапом, и рисовать экран обновления дважды смысла нет.
 * Логика проверки и установки от UI не зависит и переживёт этот переезд без изменений.
 */
object UpdatePrompt {
    private val CHECK_INTERVAL = TimeUnit.HOURS.toMillis(24)

    /**
     * Автоматическая проверка при запуске: не чаще раза в сутки, с уважением
     * к выключенной автопроверке и к версии, от которой пользователь отказался.
     */
    fun checkInBackground(activity: Activity, scope: CoroutineScope, mixedPort: Int? = null) {
        val store = AppStore(activity)

        if (!store.autoCheckUpdate) return
        if (System.currentTimeMillis() - store.lastUpdateCheck < CHECK_INTERVAL) return

        scope.launch {
            val available = runCatching {
                Updater.check(activity, store.nightlyChannel, mixedPort)
            }.getOrNull() ?: return@launch

            store.lastUpdateCheck = System.currentTimeMillis()

            if (available.manifest.versionCode == store.skippedVersionCode) return@launch

            withContext(Dispatchers.Main) { ask(activity, scope, store, available, mixedPort) }
        }
    }

    /**
     * Ручная проверка — из «О приложении». В отличие от фоновой, не смотрит
     * ни на интервал, ни на пропущенную версию и всегда говорит результат:
     * молчание в ответ на нажатие кнопки выглядит как поломка.
     */
    fun checkNow(activity: Activity, scope: CoroutineScope, mixedPort: Int? = null) {
        val store = AppStore(activity)

        scope.launch {
            val available = runCatching {
                Updater.check(activity, store.nightlyChannel, mixedPort)
            }.onFailure {
                Log.w("UpdatePrompt: проверка не удалась", it)
            }.getOrNull()

            store.lastUpdateCheck = System.currentTimeMillis()

            withContext(Dispatchers.Main) {
                if (available == null) {
                    Toast.makeText(activity, "Обновлений нет", Toast.LENGTH_SHORT).show()
                } else {
                    ask(activity, scope, store, available, mixedPort)
                }
            }
        }
    }

    private fun ask(
        activity: Activity,
        scope: CoroutineScope,
        store: AppStore,
        available: Updater.Available,
        mixedPort: Int?,
    ) {
        if (activity.isFinishing) return

        AlertDialog.Builder(activity)
            .setTitle("Доступно обновление ${available.manifest.version}")
            .setMessage(available.manifest.notes.ifBlank { "Вышла новая версия приложения." })
            .setPositiveButton("Обновить") { _, _ ->
                if (!ApkInstaller.canInstall(activity)) {
                    // Без разрешения установка молча не начнётся, поэтому объясняем,
                    // куда идти, и ведём в системные настройки.
                    AlertDialog.Builder(activity)
                        .setTitle("Нужно разрешение")
                        .setMessage(
                            "Чтобы приложение могло обновить себя, разрешите ему установку. " +
                                "Система откроет свой экран — включите там переключатель."
                        )
                        .setPositiveButton("Открыть") { _, _ -> ApkInstaller.requestPermission(activity) }
                        .setNegativeButton("Отмена", null)
                        .show()
                    return@setPositiveButton
                }

                download(activity, scope, available, mixedPort)
            }
            .setNegativeButton("Позже", null)
            .setNeutralButton("Пропустить эту версию") { _, _ ->
                store.skippedVersionCode = available.manifest.versionCode
            }
            .show()
    }

    private fun download(
        activity: Activity,
        scope: CoroutineScope,
        available: Updater.Available,
        mixedPort: Int?,
    ) {
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(activity).apply {
            setTitle("Загрузка ${available.manifest.version}")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }

        scope.launch {
            val result = Updater.download(activity, available, mixedPort) { received, total ->
                if (total > 0) {
                    scope.launch(Dispatchers.Main) {
                        progress.progress = ((received * 100) / total).toInt()
                    }
                }
            }

            withContext(Dispatchers.Main) {
                runCatching { progress.dismiss() }

                result.fold(
                    onSuccess = { apk ->
                        runCatching { ApkInstaller.install(activity, apk) }.onFailure {
                            Log.w("UpdatePrompt: установка не запустилась", it)
                            Toast.makeText(
                                activity,
                                "Не удалось запустить установку: ${it.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onFailure = {
                        Toast.makeText(
                            activity,
                            "Не удалось скачать обновление: ${it.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }
}
