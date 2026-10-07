package com.github.kr328.clash.service.report

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.freeze.FreezeOutcome
import com.github.kr328.clash.service.freeze.FreezeVerdict
import com.github.kr328.clash.service.freeze.NetworkKey
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.UUID

// Отчёт прослойке о качестве узлов: сборщик и отправка — в ядре
// (native/report), здесь — когда собирать, в какой сети идут замеры, итоги
// проверки 16–20 и отправка после планового обновления подписки. Копится
// только у подписки с защищённым каналом: только им отчёт и может уйти.
object ClientReports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    @Synchronized
    fun profileLoaded(uuid: UUID, secure: Boolean) {
        val store = if (secure) storeFile(context, uuid).absolutePath else ""

        call("target") { put("store", store) }
    }

    @Synchronized
    fun sessionStopped() {
        call("target") { put("store", "") }
    }

    // Сеть и вид, последними отданные ядру: повтор оно пропускает само, здесь
    // он не зовётся вовсе. Замок свой: сеть называет поток системных
    // колбэков, и ждать смены цели отчёта (запись накопленного) ему незачем
    private var named: Pair<String, String>? = null
    private val namedLock = Any()

    // Сторож туннеля назвал сеть (при старте, смене, позднем приходе свойств);
    // null — сети нет. Зовётся в момент события: замеры до него — старой сети
    fun network(seen: NetworkKey.Seen?) {
        synchronized(namedLock) {
            val key = NetworkKey.keyOf(seen).orEmpty()
            val kind = if (key.isEmpty()) "" else NetworkKey.kindOf(seen)

            if (named == key to kind) return

            val at = System.currentTimeMillis()

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

    // Плановое обновление подписки удалось: если подошло время, отчёт уходит
    suspend fun afterScheduledUpdate(uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        if (!imported.secure) return

        val store = storeFile(context, uuid)

        if (!store.isFile) return

        withContext(Dispatchers.IO) {
            call("send") {
                put("store", store.absolutePath)
                put("url", imported.source)
                put("path", context.importedDir.resolve(uuid.toString()).absolutePath)
            }
        }
    }

    fun forget(context: Context, uuid: UUID) {
        storeFile(context, uuid).delete()
    }
}
