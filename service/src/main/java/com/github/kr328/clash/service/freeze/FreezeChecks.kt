package com.github.kr328.clash.service.freeze

import android.content.Context
import android.net.Network
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.readPanelInfo
import com.github.kr328.clash.service.util.sendFreezeMarksChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// clod:freeze — проверка 16–20: режется ли трафик через узел в этой сети.
//
// Механизм — в ядре Clod Core: узел качает 64 КБ и отвечает ok / frozen / dead
// / unknown. Здесь — когда звать, хранение по подписке и сети и пометки для
// экрана. С узлами служба ничего не делает: пинг и выбор как были.
//
// Проверяется путь от адреса клиента до адреса сервера — туннель тут ни при
// чём: с ним узлы берутся из работающего ядра, без него разбираются из файла
// подписки, как в замере задержек. Поводы захода: подписка загружена в ядро
// (старт туннеля, смена, обновление), сеть сменилась, тик раз в час при
// работающем туннеле; без туннеля — обновление активной подписки и первый
// запрос пометок экраном в этом процессе.
// Проверяются отпечатки без итога в текущей сети, «работает» и «режется»
// старше 3 суток, «не отвечает» и попытки без итога старше 6 часов; одинаковые
// узлы делят итог по отпечатку. Смена сети или подписки посреди захода его
// бросает; заход, где не прошло ничего нигде, не записывается. Ядро без
// отпечатков — молчит.
object FreezeChecks {
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=65536"
    private const val DOWNLOAD_SIZE = 65_536
    private const val DOWNLOAD_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_STALL_MS = 4_000
    private const val TICK_MS = 60L * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    private val outcomesSerializer = MapSerializer(String.serializer(), FreezeOutcome.serializer())

    private val json = Json { ignoreUnknownKeys = true }

    // Номер захода: смена сети поднимает его, заход со старым номером бросается
    private val epoch = AtomicLong()

    @Volatile
    private var reason = ""

    // Сеть, которую выбрал сторож туннеля; без туннеля — активная сеть системы
    @Volatile
    private var network: Network? = null

    // Подписка в работающем ядре; null — туннеля нет
    @Volatile
    private var loaded: UUID? = null

    @Volatile
    private var marksOf: Pair<UUID, Map<String, String>>? = null

    private var ticker: Job? = null

    private val context: Context
        get() = Global.application

    init {
        scope.launch {
            for (kick in kicks) {
                try {
                    pass()
                } catch (e: Exception) {
                    Log.w("Freeze pass failed: $e", e)
                }
            }
        }
    }

    // Пометки подписки в текущей сети: имя узла → frozen | dead. Свежий процесс
    // их ещё не считал — заход пойдёт, экран узнает по оповещению
    fun marks(uuid: UUID): Map<String, String> {
        val known = marksOf

        if (known?.first != uuid) {
            kick("marks asked", drop = false)

            return emptyMap()
        }

        return known.second
    }

    // Заготовка для прослойки: всё, что клиент знает о проверке 16–20 этой
    // подписки, одним документом — по сетям (хеш) и отпечаткам узлов, с именем
    // узла и кодом ответа последней проверки. Куда и когда отправлять, решится
    // позже; здесь только форма
    @Suppress("unused")
    fun report(uuid: UUID): String {
        val file = FreezeStore.load(context, uuid)

        return buildJsonObject {
            put("version", 1)
            put("platform", "android")
            put("client", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty())
            put("subscription", uuid.toString())
            put("networks", json.encodeToJsonElement(FreezeFile.serializer(), file).jsonObject.getValue("networks"))
        }.toString()
    }

    // Подписка загружена в ядро: старт туннеля, смена, обновление, перезапуск.
    // Ядро отменяет пробы старого конфига — идущий заход бросается
    fun profileLoaded(uuid: UUID) {
        loaded = uuid

        synchronized(this) {
            if (ticker == null) {
                ticker = scope.launch {
                    while (isActive) {
                        delay(TICK_MS)

                        kick("hourly tick", drop = false)
                    }
                }
            }
        }

        kick("profile loaded", drop = true)
    }

    // Туннель остановлен: без ядра проверять нечем, тик не нужен
    fun sessionStopped() {
        loaded = null
        network = null

        synchronized(this) {
            ticker?.cancel()
            ticker = null
        }
    }

    // Сторож туннеля узнал сеть при старте или её свойства (маршруты, DNS)
    // дошли позже: заход, если он ждал ключа, идёт
    fun networkSeen(chosen: Network?) {
        network = chosen

        kick("network seen", drop = false)
    }

    // Сторож туннеля сменил сеть — идущий заход бросается
    fun networkChanged(chosen: Network?) {
        network = chosen

        kick("network changed", drop = true)
    }

    // Подписка обновлена без туннеля: узлы читаются из файла
    fun profileUpdated(uuid: UUID) {
        if (loaded != null) return

        if (ServiceStore(context).activeProfile != uuid) return

        kick("subscription updated", drop = false)
    }

    private fun kick(why: String, drop: Boolean) {
        reason = why

        if (drop) epoch.incrementAndGet()

        kicks.trySend(Unit)
    }

    private fun fingerprints(path: java.io.File?): Map<String, String> = try {
        json.decodeFromString(mapSerializer, Clash.queryNodeFingerprints(path))
    } catch (e: Exception) {
        Log.w("Freeze fingerprints: $e", e)

        emptyMap()
    }

    private fun checks(path: java.io.File?, names: List<String>): Map<String, FreezeOutcome> {
        val request = buildJsonObject {
            put("path", path?.absolutePath.orEmpty())
            put("names", json.encodeToJsonElement(ListSerializer(String.serializer()), names))
            put("url", DOWNLOAD_URL)
            put("size", DOWNLOAD_SIZE)
            put("timeout", DOWNLOAD_TIMEOUT_MS)
            put("stall", DOWNLOAD_STALL_MS)
        }

        return try {
            json.decodeFromString(outcomesSerializer, Clash.downloadChecks(request.toString()))
        } catch (e: Exception) {
            Log.w("Freeze checks: $e", e)

            emptyMap()
        }
    }

    private fun publish(uuid: UUID, marks: Map<String, String>) {
        val previous = marksOf

        marksOf = uuid to marks

        if (previous?.first != uuid || previous.second != marks) {
            context.sendFreezeMarksChanged(uuid)
        }
    }

    private fun counted(outcomes: Collection<FreezeOutcome>): String =
        listOf(FreezeVerdict.OK, FreezeVerdict.FROZEN, FreezeVerdict.DEAD, "unknown")
            .joinToString(", ") { verdict -> "${outcomes.count { it.verdict == verdict }} $verdict" }

    private fun pass() {
        val startedAt = epoch.get()
        val why = reason
        val uuid = ServiceStore(context).activeProfile ?: run {
            marksOf = null

            return
        }

        // Включает проверку только панель — заголовком clod-16-20-check: true
        if (context.readPanelInfo(uuid)?.freezeCheck != true) {
            publish(uuid, emptyMap())

            return
        }

        // Туннель с другой подпиской — переходное состояние, следующий повод придёт сам
        val running = loaded
        if (running != null && running != uuid) return

        val path = if (running == null) context.importedDir.resolve(uuid.toString()) else null

        val fingerprints = fingerprints(path)
        if (fingerprints.isEmpty()) {
            publish(uuid, emptyMap())

            return
        }

        // Сеть не распознана (сторож туннеля её ещё не назвал, активна чужая VPN):
        // ни пометок, ни проверок — следующий повод придёт от сторожа
        val key = NetworkKey.keyOf(NetworkKey.seen(context, network))
        if (key == null) {
            publish(uuid, emptyMap())

            return
        }

        val now = System.currentTimeMillis() / 1000
        val live = fingerprints.values.toSet()

        var file = FreezePlan.prune(FreezeStore.load(context, uuid), now, live)
        var net = file.networks[key] ?: FreezeNetwork()

        // Пометки этой сети показываются сразу, не дожидаясь проверок
        publish(uuid, FreezePlan.marks(fingerprints, net))

        val due = FreezePlan.due(fingerprints, net, now)
        var stored = false

        if (due.isNotEmpty()) {
            val outcomes = checks(path, due)

            val same = epoch.get() == startedAt &&
                ServiceStore(context).activeProfile == uuid &&
                fingerprints(path) == fingerprints &&
                NetworkKey.keyOf(NetworkKey.seen(context, network)) == key

            if (!same) {
                ServiceLog.mark("freeze: $why, the network or the subscription changed mid-pass, results dropped")

                return
            }

            stored = FreezePlan.worthRecording(outcomes.values)

            if (stored) {
                val nodes = net.nodes.toMutableMap()

                for ((name, outcome) in outcomes) {
                    val fingerprint = fingerprints[name] ?: continue

                    nodes[fingerprint] = FreezePlan.apply(nodes[fingerprint], outcome, now, name)
                }

                net = net.copy(nodes = nodes)
            }

            ServiceLog.mark(
                "freeze: $why, ${outcomes.size} of ${fingerprints.size} node(s) checked in network $key — " +
                    counted(outcomes.values) + if (stored) "" else "; nothing passed anywhere, not recorded",
            )
        }

        val known = net.lastSeen != 0L

        if (stored || (known && now - net.lastSeen >= FreezePlan.TOUCH_AFTER)) {
            net = net.copy(lastSeen = now)
            file = file.copy(networks = file.networks + (key to net))

            try {
                FreezeStore.save(context, uuid, file)
            } catch (e: Exception) {
                Log.w("Freeze results were not saved: $e", e)
            }
        }

        publish(uuid, FreezePlan.marks(fingerprints, net))
    }
}
