package com.github.kr328.clash.service.freeze

import android.content.Context
import android.net.Network
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.ServiceLog
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.report.ClientReports
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ProfileInputs
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.readPanelInfo
import com.github.kr328.clash.service.util.sendFreezeMarksChanged
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// clod:freeze — проверка 16–20: обрезается ли скачивание через узел в этой сети.
//
// Механизм — в ядре Clod Core: узел качает 64 КБ и отвечает ok / frozen / dead
// / unknown. Здесь — когда звать, хранение по подписке и сети и пометка для
// экрана. Итог один — обрезается ли скачивание: «работает» или «режется»; всё
// прочее (в том числе dead) итогом не считается и записывается только как
// попытка. С узлами служба ничего не делает: пинг и выбор как были.
//
// Проверяется путь от адреса клиента до адреса сервера — туннель тут ни при
// чём: с ним узлы берутся из работающего ядра, без него разбираются из файла
// подписки, как в замере задержек. Поводы захода: подписка загружена в ядро
// (старт туннеля, смена, обновление), обновление или сохранение подписки тем же
// конфигом, сеть сменилась, тик раз в час при работающем туннеле; без туннеля —
// обновление или сохранение активной подписки и запрос пометок экраном, когда
// показанные пометки не этой подписки или не этой сети.
// Проверяются отпечатки без итога в текущей сети, итоги старше 3 суток,
// попытки без итога старше 6 часов; одинаковые узлы делят итог по отпечатку.
// Обрезанный узел через 10 с проверяется ещё дважды вместе с контрольным,
// рабочим здесь: «режется» — только если обрезались все три проверки, а
// контрольный прошёл; прошла последняя — «работает»; иначе (сравнить не с чем,
// контрольный не прошёл) итога нет. Смена сети или подписки посреди захода его
// бросает. Ядро без отпечатков — молчит.
object FreezeChecks {
    private const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=65536"
    private const val DOWNLOAD_SIZE = 65_536
    private const val DOWNLOAD_TIMEOUT_MS = 10_000
    private const val DOWNLOAD_STALL_MS = 4_000
    private const val TICK_MS = 60L * 60 * 1000

    // Пауза между повторами обрезанных узлов
    private const val WAVE_PAUSE_MS = 10_000L

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

    // Ключ сети, последней названной сторожем
    @Volatile
    private var networkKey: String? = null

    // Подписка в работающем ядре; null — туннеля нет
    @Volatile
    private var loaded: UUID? = null

    // Показанные пометки: подписка, сеть (null — не распознана) и имя узла → frozen
    private data class Shown(val uuid: UUID, val net: String?, val marks: Map<String, String>)

    @Volatile
    private var shown: Shown? = null

    // Экран получил пустые пометки, потому что показанные посчитаны не для этой
    // сети или подписки: следующие посчитанные уходят ему оповещением, даже если
    // совпали с прежними
    @Volatile
    private var owed = false

    private var ticker: Job? = null

    private val context: Context
        get() = Global.application

    init {
        scope.launch {
            for (kick in kicks) {
                try {
                    pass()
                } catch (e: CancellationException) {
                    // Сам цикл заходов не отменяется никогда: такое исключение — не
                    // отмена, и заходы после него продолжаются
                    if (!isActive) throw e

                    Log.w("Freeze pass failed: $e", e)
                } catch (e: Exception) {
                    Log.w("Freeze pass failed: $e", e)
                }
            }
        }
    }

    // Пометки подписки в текущей сети: имя узла → frozen. Показанные посчитаны
    // для другой подписки или сети (свежий процесс, без туннеля о смене сети
    // никто не сообщает) — их нет, заход пойдёт, экран узнает по оповещению
    fun marks(uuid: UUID): Map<String, String> {
        val known = shown

        if (known?.uuid != uuid || known.net != NetworkKey.keyOf(NetworkKey.seen(context, network))) {
            owed = true

            kick("marks asked", drop = false)

            return emptyMap()
        }

        return known.marks
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

        synchronized(this) {
            network = null
            networkKey = null

            ticker?.cancel()
            ticker = null
        }
    }

    // Сторож туннеля узнал сеть при старте или её свойства (маршруты, DNS)
    // дошли позже: заход, если он ждал ключа, идёт. Свойства меняются часто,
    // ключ — редко: без его смены звать заход незачем
    @Synchronized
    fun networkSeen(chosen: Network, seen: NetworkKey.Seen?) {
        // Сторож отписывается уже после остановки: его поздняя сеть осталась
        // бы у заходов без туннеля, которые берут активную сеть сами
        if (!StatusProvider.serviceRunning) return

        val key = NetworkKey.keyOf(seen)

        if (chosen == network && key == networkKey) return

        network = chosen
        networkKey = key

        kick("network seen", drop = false)
    }

    // Сторож туннеля сменил сеть — идущий заход бросается
    @Synchronized
    fun networkChanged(chosen: Network?) {
        if (!StatusProvider.serviceRunning) return

        network = chosen
        networkKey = chosen?.let { NetworkKey.keyOf(NetworkKey.seen(context, it)) }

        kick("network changed", drop = true)
    }

    // Подписка обновлена или её свойства сохранены без туннеля: узлы читаются
    // из файла. С туннелем повод даёт загрузка: другой конфиг ядро загрузит и
    // позовёт profileLoaded, тот же — profileKept. Заход отсюда шёл бы рядом с
    // перезагрузкой ядра, и отменённые ею проверки записались бы как попытки
    fun profileUpdated(uuid: UUID) {
        if (loaded != null) return

        if (ServiceStore(context).activeProfile != uuid) return

        kick("subscription updated", drop = false)
    }

    // Подписку в ядре обновили или сохранили тем же конфигом: ядро её не
    // перезагружало, идущий заход не бросается. Панель могла включить или
    // выключить проверку — пометки сверяются сразу
    fun profileKept(uuid: UUID) {
        if (loaded != uuid) return

        kick("subscription kept", drop = false)
    }

    private fun kick(why: String, drop: Boolean) {
        reason = why

        if (drop) epoch.incrementAndGet()

        kicks.trySend(Unit)
    }

    private fun fingerprints(path: File?): Map<String, String> = try {
        json.decodeFromString(mapSerializer, Clash.queryNodeFingerprints(path))
    } catch (e: Exception) {
        Log.w("Freeze fingerprints: $e", e)

        emptyMap()
    }

    // Отпечаток файлов подписки: без туннеля по нему видно, что она сменилась
    // посреди захода, — без второго разбора
    private fun inputsOf(path: File): String? = try {
        ProfileInputs.fingerprint(path)
    } catch (e: IOException) {
        Log.w("Freeze inputs: $e", e)

        null
    }

    private fun checks(path: File?, names: List<String>): Map<String, FreezeOutcome> {
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

    private fun publish(uuid: UUID, marks: Map<String, String>, net: String? = null) {
        val previous = shown

        shown = Shown(uuid, net, marks)

        if (owed || previous?.uuid != uuid || previous.marks != marks) {
            owed = false

            context.sendFreezeMarksChanged(uuid)
        }
    }

    private fun counted(outcomes: Collection<FreezeOutcome>): String {
        val named = listOf(FreezeVerdict.OK, FreezeVerdict.FROZEN)

        return (named.map { verdict -> "${outcomes.count { it.verdict == verdict }} $verdict" } +
            "${outcomes.count { it.verdict !in named }} without a result").joinToString(", ")
    }

    private suspend fun pass() {
        val startedAt = epoch.get()
        val why = reason
        val uuid = ServiceStore(context).activeProfile ?: run {
            shown = null

            return
        }

        // Сеть не распознана (сторож туннеля её ещё не назвал, активна чужая VPN):
        // ни пометок, ни проверок — следующий повод придёт от сторожа или экрана
        val seen = NetworkKey.seen(context, network)
        val key = NetworkKey.keyOf(seen)

        // Включает проверку только панель — заголовком clod-16-20-check: true
        val panel = context.readPanelInfo(uuid)
        if (panel?.freezeCheck != true) {
            publish(uuid, emptyMap(), key)

            return
        }

        // Туннель с другой подпиской — переходное состояние: загрузка подписки
        // в ядро позовёт заход сама
        val running = loaded
        if (running != null && running != uuid) return

        // Туннель ещё грузит подписку в ядро: проверки начнёт загрузка, а
        // пометки, если на экране нет пометок этой сети, показываются сразу — из
        // файла. Сеть ещё не названа (активна наша VPN) — показывать вместо
        // пометок нечего; та же сеть — они уже верны
        val starting = running == null && StatusProvider.serviceRunning
        if (starting && shown?.uuid == uuid && (key == null || key == shown?.net)) return

        if (key == null) {
            publish(uuid, emptyMap())

            return
        }

        val path = if (running == null) context.importedDir.resolve(uuid.toString()) else null

        val inputs = path?.let(::inputsOf)
        val fingerprints = fingerprints(path)
        if (fingerprints.isEmpty()) {
            publish(uuid, emptyMap(), key)

            return
        }

        val now = System.currentTimeMillis() / 1000
        val live = fingerprints.values.toSet()

        var file = FreezePlan.prune(FreezeStore.load(context, uuid), now, live)
        var net = file.networks[key] ?: FreezeNetwork()
        var stored = false

        // Пометки этой сети показываются сразу, не дожидаясь проверок
        publish(uuid, FreezePlan.marks(fingerprints, net), key)

        // Серверы только для мобильной сети вне сети SIM скрыты — не проверяются;
        // их прежние пометки остаются
        val cellular = NetworkKey.cellular(seen) == true
        val checkable = fingerprints.filterKeys { panel?.hidesOffMobile(it, cellular) != true }
        val due = if (starting) emptyList() else FreezePlan.due(checkable, net, now)

        if (due.isNotEmpty()) {
            val first = checks(path, due)
            val suspects = FreezePlan.suspects(first)
            val outcomes = FreezePlan.unconfirmed(first).toMutableMap()

            if (suspects.isNotEmpty()) {
                val control = FreezePlan.control(checkable, net, outcomes, suspects)

                if (control == null) {
                    // Сравнить не с чем — повторов нет, значков тоже: это скорее плохая сеть
                    ServiceLog.mark("freeze: $why, ${suspects.size} node(s) cut, no node works here to compare with, not marked")
                } else {
                    outcomes += FreezePlan.repeated(suspects, control, { ServiceLog.mark("freeze: $why, $it") }) { batch ->
                        delay(WAVE_PAUSE_MS)

                        if (epoch.get() != startedAt) null else checks(path, batch)
                    }
                }
            }

            val same = epoch.get() == startedAt &&
                ServiceStore(context).activeProfile == uuid &&
                (if (path == null) fingerprints(null) == fingerprints else inputs != null && inputsOf(path) == inputs) &&
                NetworkKey.keyOf(NetworkKey.seen(context, network)) == key

            if (!same) {
                ServiceLog.mark("freeze: $why, the network or the subscription changed mid-pass, results dropped")

                return
            }

            // Проверки, оборванные ядром (его загрузка или сброс), не записываются:
            // эти узлы остаются в очереди
            net = net.copy(nodes = FreezePlan.recorded(net.nodes, fingerprints, outcomes, now))
            stored = true

            ClientReports.noteFreeze(uuid, path, key, NetworkKey.kindOf(seen), outcomes)

            val missed = due.size - first.size

            ServiceLog.mark(
                "freeze: $why, ${outcomes.size} of ${fingerprints.size} node(s) checked in network $key — " +
                    counted(outcomes.values) + if (missed > 0) "; $missed check(s) cut short, retried next time" else "",
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

        publish(uuid, FreezePlan.marks(fingerprints, net), key)
    }
}
