package com.github.kr328.clash.service.report

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.freeze.FreezeOutcome
import com.github.kr328.clash.service.freeze.FreezeVerdict
import com.github.kr328.clash.service.freeze.NetworkKey
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Отчёт прослойке о качестве узлов: сборщик и отправка — в ядре
// (native/report), здесь — когда собирать, в какой сети идут замеры, итоги
// проверки 16–20 и отправка после планового обновления подписки, а если она
// обновляется реже — и между обновлениями. Копится только у подписки с
// защищённым каналом: только им отчёт и может уйти.
object ClientReports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Цель сбора и сеть уходят в ядро по очереди и не с потока вызвавшего:
    // сеть называет общий поток системных колбэков процесса, загрузка и
    // остановка сессии ждать сборщика тоже не должны. Очередь одна — порядок
    // событий тот же, что у вызовов
    private val serial = Executors.newSingleThreadExecutor { Thread(it, "ClientReports") }

    // Отправки (после обновления и между обновлениями) не идут разом
    private val sendLock = Mutex()

    // Между плановыми обновлениями, если они реже: столько же, сколько ядро
    // выдерживает между отправками, и запас — чтобы первая не пришлась раньше
    // срока, отсчитанного от отправки после обновления, заводящего их
    private val BETWEEN = TimeUnit.HOURS.toMillis(6)
    private val BETWEEN_SLACK = TimeUnit.MINUTES.toMillis(10)

    private val context: Context
        get() = Global.application

    fun storeFile(context: Context, uuid: UUID): File =
        context.filesDir.resolve("report").resolve("$uuid.json")

    private suspend fun collected(uuid: UUID): Boolean =
        ImportedDao().queryByUUID(uuid)?.secure == true

    private fun call(op: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Boolean {
        return try {
            Clash.clientReport(buildJsonObject {
                put("op", op)
                fill()
            }.toString())

            true
        } catch (e: Exception) {
            Log.w("Client report $op: $e", e)

            false
        }
    }

    // Подписка загружена в ядро: собирать, если у неё защищённый канал
    fun profileLoaded(uuid: UUID, secure: Boolean) {
        val store = if (secure) storeFile(context, uuid).absolutePath else ""

        serial.execute { call("target") { put("store", store) } }
    }

    fun sessionStopped() {
        serial.execute { call("target") { put("store", "") } }
    }

    // Сеть и вид, последними отданные ядру: повтор оно пропускает само, здесь
    // он не зовётся вовсе. Только в очереди
    private var named: Pair<String, String>? = null

    // Сторож туннеля назвал сеть (при старте, смене, позднем приходе свойств);
    // null — сети нет. Момент берётся при событии: замеры до него — старой сети
    fun network(seen: NetworkKey.Seen?) {
        val key = NetworkKey.keyOf(seen).orEmpty()
        val kind = if (key.isEmpty()) "" else NetworkKey.kindOf(seen)
        val at = System.currentTimeMillis()

        serial.execute {
            if (named == key to kind) return@execute

            // Несостоявшийся вызов не запоминается: следующее событие повторит
            val sent = call("network") {
                put("net", key)
                put("kind", kind)
                put("at", at)
            }

            if (sent) named = key to kind
        }
    }

    // Проверка 16–20 записала итоги в сети key; path — папка подписки, когда
    // узлы берутся из файла, а не из работающего ядра
    fun noteFreeze(uuid: UUID, path: File?, key: String, kind: String, outcomes: Map<String, FreezeOutcome>, now: Long) {
        val verdicts = outcomes.filterValues { FreezeVerdict.settled(it.verdict) }

        if (verdicts.isEmpty()) return

        scope.launch {
            if (!collected(uuid)) return@launch

            call("freeze") {
                put("store", storeFile(context, uuid).absolutePath)
                put("path", path?.absolutePath.orEmpty())
                put("net", key)
                put("kind", kind)
                putJsonObject("verdicts") {
                    for ((name, outcome) in verdicts) {
                        putJsonObject(name) {
                            put("verdict", outcome.verdict)
                            put("status", outcome.status)
                            put("at", now)
                        }
                    }
                }
            }
        }
    }

    // Плановое обновление подписки удалось: если подошло время, отчёт уходит.
    // Отправки между обновлениями заводятся один раз (после неудачного
    // обновления — заново) и идут своим ходом: сброс отсчёта на каждом
    // обновлении ставил бы их сразу за отправкой после него, и ядро,
    // выдерживая 6 часов, пропускало бы каждую вторую
    suspend fun afterScheduledUpdate(context: Context, uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        // Копить нечего (подписка ещё не работала в туннеле) — будить процесс
        // ради отправки незачем
        if (between(imported) && storeFile(context, uuid).isFile) {
            val request = PeriodicWorkRequestBuilder<ClientReportWorker>(BETWEEN, TimeUnit.MILLISECONDS)
                .setInitialDelay(BETWEEN + BETWEEN_SLACK, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_UUID to uuid.toString()))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(betweenName(uuid), ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            stopBetween(context, uuid)
        }

        send(context, imported)
    }

    // Обновление подписки не удалось (любое): отправки между обновлениями
    // ждут следующего удачного планового
    fun updateFailed(context: Context, uuid: UUID) {
        stopBetween(context, uuid)
    }

    // Отправка между плановыми обновлениями. Подписки нет, канал выключен или
    // обновления стали частыми (или выключены) — отсчёт снимается
    suspend fun betweenUpdates(context: Context, uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid)

        if (imported == null || !between(imported)) {
            stopBetween(context, uuid)

            return
        }

        send(context, imported)
    }

    // Плановые обновления реже, чем отчёт может уходить
    private fun between(imported: Imported): Boolean =
        imported.secure && imported.interval > BETWEEN

    private suspend fun send(context: Context, imported: Imported) {
        if (!imported.secure) return

        val store = storeFile(context, imported.uuid)

        if (!store.isFile) return

        sendLock.withLock {
            withContext(Dispatchers.IO) {
                call("send") {
                    put("store", store.absolutePath)
                    put("url", imported.source)
                    put("path", context.importedDir.resolve(imported.uuid.toString()).absolutePath)
                }
            }
        }
    }

    fun forget(context: Context, uuid: UUID) {
        stopBetween(context, uuid)

        storeFile(context, uuid).delete()
    }

    private fun stopBetween(context: Context, uuid: UUID) {
        WorkManager.getInstance(context).cancelUniqueWork(betweenName(uuid))
    }

    private fun betweenName(uuid: UUID) = "client-report-$uuid"

    internal const val KEY_UUID = "uuid"
}

class ClientReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uuid = inputData.getString(ClientReports.KEY_UUID)?.let(UUID::fromString) ?: return Result.failure()

        ClientReports.betweenUpdates(applicationContext, uuid)

        return Result.success()
    }
}
