package com.github.kr328.clash.service.report

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.DeviceCard
import com.github.kr328.clash.service.util.UpdateSchedule
import com.github.kr328.clash.service.util.deviceCard
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.readPanelInfo
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
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Отчёт прослойке о качестве узлов: сборщик и отправка — в ядре
// (native/report), здесь — когда собирать, в какой сети идут замеры, итоги
// проверки 16–20 и отправка после планового обновления подписки, а если она
// обновляется реже — и между обновлениями. Копится только у подписки с
// защищённым каналом — только им отчёт и может уйти — и пока прослойка в ответе
// подписки по каналу не сказала, что отчёты не принимает; сказала — накопленное
// стирается.
object ClientReports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Цель сбора и сеть уходят в ядро по очереди и не с потока вызвавшего:
    // сеть называет общий поток системных колбэков процесса, загрузка и
    // остановка сессии ждать сборщика тоже не должны. Очередь одна — порядок
    // событий тот же, что у вызовов
    private val serial = Executors.newSingleThreadExecutor { Thread(it, "ClientReports") }

    // Отправки (после обновления и между обновлениями) не идут разом
    private val sendLock = Mutex()

    // Решение «копится ли» и запись в накопленное мимо сборщика (итоги 16–20,
    // стирание) идут по одной: итог, решённый до стирания, иначе вернул бы файл
    private val storeLock = Mutex()

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
        ImportedDao().queryByUUID(uuid)?.let { collected(it.uuid, it.secure) } == true

    // Копится ли отчёт: защищённый канал и прослойка сказала, что отчёты
    // принимает. Без её метки (прослойка старее, подписка ещё не обновлялась) —
    // не копится
    private fun collected(uuid: UUID, secure: Boolean): Boolean =
        secure && context.readPanelInfo(uuid)?.report == true

    // Соль ключа сети установки: солит ключ ядро (report.NetKey), и прежнее
    // накопленное тоже. Для проверки 16–20 на устройстве ключ прежний
    private val salt: String by lazy {
        val store = ServiceStore(context)

        store.reportNetSalt.ifEmpty {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }

            bytes.joinToString("") { "%02x".format(it) }.also { store.reportNetSalt = it }
        }
    }

    private fun call(op: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Boolean {
        return try {
            Clash.clientReport(buildJsonObject {
                put("op", op)
                put("salt", salt)
                fill()
            }.toString())

            true
        } catch (e: Exception) {
            Log.w("Client report $op: $e", e)

            false
        }
    }

    // Подписка загружена в ядро: собирать, если по ней копится отчёт
    fun profileLoaded(uuid: UUID, secure: Boolean) {
        serial.execute {
            val store = if (collected(uuid, secure)) storeFile(context, uuid).absolutePath else ""

            call("target") {
                put("store", store)
                put("uuid", uuid.toString())
            }
        }

        // Сбор идёт — отправки между обновлениями заведены, если положены и их
        // не держит упавшее обновление (так и у подписок, ни разу не
        // обновлявшихся с тех пор, как условие стало их требовать)
        scope.launch {
            if (collected(uuid, secure) && !held(uuid)) {
                ImportedDao().queryByUUID(uuid)?.let { scheduleBetween(context, it) }
            }
        }
    }

    // Подписка загружена с панели (добавлена, обновлена, сохранена): отчёт по
    // ней больше не копится (прослойка сказала, что не принимает, канал
    // выключили) — накопленное стирается
    fun fetched(context: Context, uuid: UUID) {
        scope.launch {
            storeLock.withLock {
                if (!collected(uuid)) drop(context, uuid)
            }

            // Удачное обновление любого вида (и ручное, и при сохранении) снова
            // заводит отправки между обновлениями, если их сняло упавшее, — и
            // заводит вовсе у подписки без автообновления: плановых загрузок,
            // после которых они заводились, у неё нет. Само ручное отчёт не шлёт
            hold(uuid, false)

            ImportedDao().queryByUUID(uuid)?.let { scheduleBetween(context, it) }
        }
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
        val raw = NetworkKey.keyOf(seen).orEmpty()
        val kind = if (raw.isEmpty()) "" else NetworkKey.kindOf(seen)
        val at = System.currentTimeMillis()

        // Соль при первом обращении читает хранилище — не на потоке сторожа
        serial.execute {
            if (named == raw to kind) return@execute

            // Несостоявшийся вызов не запоминается: следующее событие повторит
            val sent = call("network") {
                put("net", raw)
                put("kind", kind)
                put("at", at)
            }

            if (sent) named = raw to kind
        }
    }

    // Проверка 16–20 записала итоги в сети key; path — папка подписки, когда
    // узлы берутся из файла, а не из работающего ядра. Час итога — когда он
    // лёг в отчёт: его ставит ядро
    fun noteFreeze(uuid: UUID, path: File?, key: String, kind: String, outcomes: Map<String, FreezeOutcome>) {
        val verdicts = outcomes.filterValues { FreezeVerdict.settled(it.verdict) }

        if (verdicts.isEmpty()) return

        scope.launch {
            storeLock.withLock {
                if (!collected(uuid)) return@withLock

                call("freeze") {
                    put("store", storeFile(context, uuid).absolutePath)
                    put("uuid", uuid.toString())
                    put("path", path?.absolutePath.orEmpty())
                    put("net", key)
                    put("kind", kind)
                    putJsonObject("verdicts") {
                        for ((name, outcome) in verdicts) {
                            putJsonObject(name) {
                                put("verdict", outcome.verdict)
                                put("status", outcome.status)
                            }
                        }
                    }
                }
            }
        }
    }

    // Плановое обновление подписки удалось: если подошло время, отчёт уходит.
    // Отправки между обновлениями заводятся один раз (после неудачного
    // обновления — заново, после удачного) и идут своим ходом: сброс отсчёта на
    // каждом обновлении ставил бы их сразу за отправкой после него, и ядро,
    // выдерживая 6 часов, пропускало бы каждую вторую
    suspend fun afterScheduledUpdate(context: Context, uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        hold(uuid, false)

        scheduleBetween(context, imported)

        send(context, imported)
    }

    // Отправки между обновлениями — пока подписка собирается и обновляется реже
    // раза в 6 ч (или не обновляется сама). Наличие файла не условие: он
    // появляется через несколько минут сбора, а метка приёма могла прийти этим
    // же обновлением — пустую отправку ядро отбросит само. Без сети процесс не
    // будится: отправка дожидается сети, а не следующего срока. UPDATE не сбивает
    // отсчёт уже заведённой задачи, только меняет её условия
    private fun scheduleBetween(context: Context, imported: Imported) {
        val uuid = imported.uuid

        if (between(imported)) {
            val request = PeriodicWorkRequestBuilder<ClientReportWorker>(BETWEEN, TimeUnit.MILLISECONDS)
                .setInitialDelay(BETWEEN + BETWEEN_SLACK, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInputData(workDataOf(KEY_UUID to uuid.toString()))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(betweenName(uuid), ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            stopBetween(context, uuid)
        }
    }

    // Обновление подписки не удалось (любое): отправки между обновлениями
    // ждут следующего удачного
    suspend fun updateFailed(context: Context, uuid: UUID) {
        // Подписку удалили, пока шло обновление: отметка ушла вместе с ней.
        // Под тем же замком, что и стирание накопленного
        storeLock.withLock {
            if (ImportedDao().exists(uuid)) hold(uuid, true)
        }

        stopBetween(context, uuid)
    }

    // У каждой подписки своя, уходит вместе с её накопленным
    private fun heldFile(uuid: UUID): File =
        context.filesDir.resolve("report").resolve("$uuid.held")

    private fun held(uuid: UUID): Boolean = heldFile(uuid).exists()

    @Synchronized
    private fun hold(uuid: UUID, held: Boolean) {
        val file = heldFile(uuid)

        try {
            if (held) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            Log.w("Client report hold mark: $e", e)
        }
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

    // Плановые обновления реже, чем отчёт может уходить, или выключены
    private fun between(imported: Imported): Boolean =
        collected(imported.uuid, imported.secure) &&
            (imported.interval > BETWEEN || imported.interval < UpdateSchedule.MIN_INTERVAL)

    private suspend fun send(context: Context, imported: Imported) {
        if (!collected(imported.uuid, imported.secure)) return

        val store = storeFile(context, imported.uuid)

        if (!store.isFile) return

        sendLock.withLock {
            withContext(Dispatchers.IO) {
                // Карточка устройства собирается здесь, а не берётся из ядра: отправку
                // между обновлениями будит процесс, где подписка не обновлялась, и
                // без неё отчёт ушёл бы без x-hwid — прослойка завела бы устройство
                // заново по метке установки. Не собралась — отчёт уходит по метке
                val card = try {
                    context.deviceCard()
                } catch (e: Exception) {
                    Log.w("Client report device card: $e", e)

                    null
                }

                call("send") {
                    put("store", store.absolutePath)
                    put("url", imported.source)
                    put("path", context.importedDir.resolve(imported.uuid.toString()).absolutePath)
                    putCard(card)
                }
            }
        }
    }

    // Подписка удалена
    fun forget(context: Context, uuid: UUID) {
        scope.launch {
            storeLock.withLock { drop(context, uuid) }
        }
    }

    // Накопленное — вон: сбор по нему кончается, из памяти ядра и с диска оно
    // уходит и в файл не возвращается. Только под storeLock; идущая отправка
    // дожидается — иначе её запись после стирания вернула бы файл
    private suspend fun drop(context: Context, uuid: UUID) {
        stopBetween(context, uuid)

        hold(uuid, false)

        sendLock.withLock {
            call("forget") { put("store", storeFile(context, uuid).absolutePath) }
        }
    }

    private fun stopBetween(context: Context, uuid: UUID) {
        WorkManager.getInstance(context).cancelUniqueWork(betweenName(uuid))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCard(card: DeviceCard?) {
        if (card == null || card.hwid.isEmpty()) return

        put("hwid", card.hwid)
        put("os", card.os)
        put("osVer", card.osVersion)
        put("model", card.model)
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
