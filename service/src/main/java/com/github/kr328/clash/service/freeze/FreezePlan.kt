package com.github.kr328.clash.service.freeze

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// clod:freeze — чистые решения проверки 16–20: кого проверять и что записать.
// Схема файла та же, что на ПК: сеть → отпечаток узла → итог.

@Serializable
data class FreezeNode(
    // ok / frozen; null — вердикта ещё не было
    val verdict: String? = null,
    // когда вынесен verdict, unix-секунды
    val at: Long = 0,
    // когда проверяли в последний раз, с итогом или без
    val tried: Long = 0,
    // имя узла в подписке на момент последней проверки — для прослойки, которой отпечатка мало
    val name: String? = null,
    // код ответа сайта в последней проверке (0 — ответа не было)
    val status: Int? = null,
)

@Serializable
data class FreezeNetwork(
    @SerialName("last_seen")
    val lastSeen: Long = 0,
    val nodes: Map<String, FreezeNode> = emptyMap(),
)

@Serializable
data class FreezeFile(
    val version: Int = 0,
    val networks: Map<String, FreezeNetwork> = emptyMap(),
)

// Ответ ядра на одну проверку: слово ядра и код ответа сайта (0 — ответа не было)
@Serializable
data class FreezeOutcome(
    val verdict: String = "",
    val status: Int = 0,
)

object FreezeVerdict {
    // Итог один — обрезается ли скачивание через узел: «работает» или «режется».
    // Всё прочее, что отвечает ядро (в том числе dead), итогом не считается и
    // записывается только как попытка
    const val OK = "ok"
    const val FROZEN = "frozen"

    // Итог, который показывается пометкой; «работает» пометкой не бывает
    fun mark(verdict: String?): String? = verdict?.takeIf { it == FROZEN }

    fun settled(verdict: String?): Boolean = verdict == OK || verdict == FROZEN
}

// Повтор обрезанных: снова обрезанные и прошедшие
data class FreezeWave(
    val cut: Map<String, FreezeOutcome>,
    val passed: Map<String, FreezeOutcome>,
)

object FreezePlan {
    // «Работает» и «режется» — повтор не чаще раза в 3 суток
    const val RECHECK_AFTER = 3L * 24 * 60 * 60

    // Попытка без итога — повтор через 6 часов
    const val RETRY_AFTER = 6L * 60 * 60

    // Сколько проверок подряд должны обрезаться, чтобы узел считался «режется»
    const val WAVES = 3

    const val VERSION = 2

    // Сеть, где клиент не был столько, забывается
    const val FORGET_NETWORK_AFTER = 30L * 24 * 60 * 60

    // «Были в этой сети» обновляется в файле не чаще раза в сутки
    const val TOUCH_AFTER = 24L * 60 * 60

    // Итогу 3 суток, попытке без итога 6 часов
    fun isDue(node: FreezeNode?, now: Long): Boolean {
        if (node == null) return true

        if (now - node.tried < RETRY_AFTER) return false

        return !FreezeVerdict.settled(node.verdict) || now - node.at >= RECHECK_AFTER
    }

    // Вердикт — целиком; без итога — время попытки, прежний вердикт остаётся;
    // имя и код ответа — всегда
    fun apply(node: FreezeNode?, outcome: FreezeOutcome, now: Long, name: String? = null): FreezeNode {
        val previous = node ?: FreezeNode()

        return if (FreezeVerdict.settled(outcome.verdict)) {
            FreezeNode(verdict = outcome.verdict, at = now, tried = now, name = name, status = outcome.status)
        } else {
            previous.copy(
                tried = now,
                name = name ?: previous.name,
                status = outcome.status,
            )
        }
    }

    // Обрезанные в проверке — их проверяют ещё дважды: одной обрезанной проверке
    // не верим (пауза в сети — метро, лифт — повесила бы значок на 3 суток)
    fun suspects(outcomes: Map<String, FreezeOutcome>): Set<String> =
        outcomes.filterValues { it.verdict == FreezeVerdict.FROZEN }.keys

    // До повторов у обрезанных итога нет
    fun unconfirmed(outcomes: Map<String, FreezeOutcome>): Map<String, FreezeOutcome> =
        outcomes.mapValues { (_, outcome) -> if (outcome.verdict == FreezeVerdict.FROZEN) outcome.copy(verdict = "") else outcome }

    // Контрольный узел к повторам — через него в этой сети трафик идёт: прошёл в
    // этом заходе, иначе отмечен здесь рабочим (свежее всех), последняя попытка
    // дала итог, и в заходе он не проверялся (ни он, ни узлы с его отпечатком).
    // Обрезанные и одноимённые их отпечатку не годятся. Нет такого — сравнить
    // не с чем, повторов нет
    fun control(
        fingerprints: Map<String, String>,
        network: FreezeNetwork,
        outcomes: Map<String, FreezeOutcome>,
        suspects: Set<String>,
    ): String? {
        val doubted = suspects.mapNotNull { fingerprints[it] }.toSet()
        val usable = fingerprints.filter { (name, fingerprint) -> name !in suspects && fingerprint !in doubted }

        usable.keys.filter { outcomes[it]?.verdict == FreezeVerdict.OK }.minOrNull()?.let { return it }

        val tried = outcomes.keys.mapNotNull { fingerprints[it] }.toSet()

        return usable.entries
            .filter { it.value !in tried }
            .mapNotNull { (name, fingerprint) ->
                network.nodes[fingerprint]?.takeIf { it.verdict == FreezeVerdict.OK && it.at >= it.tried }?.let { name to it.at }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    // Повтор обрезанных: контрольный не прошёл — null (это сеть, а не узлы);
    // иначе снова обрезанные и прошедшие. Повтор без итога — узел выбывает
    fun afterWave(left: Set<String>, control: String, again: Map<String, FreezeOutcome>): FreezeWave? {
        if (again[control]?.verdict != FreezeVerdict.OK) return null

        val checked = again.filterKeys { it in left }

        return FreezeWave(
            cut = checked.filterValues { it.verdict == FreezeVerdict.FROZEN },
            passed = checked.filterValues { it.verdict == FreezeVerdict.OK },
        )
    }

    // Повторы обрезанных в первой проверке; again — одна проверка узлов
    // (контрольный первым), null — повтор оборван (сменились сеть, подписка).
    // Итоги: прошедшие в повторе — «работает», обрезанные во всех WAVES
    // проверках — «режется»; контрольный не прошёл или повтор без итога — итога нет
    suspend fun repeated(
        cut: Set<String>,
        control: String,
        log: (String) -> Unit,
        again: suspend (List<String>) -> Map<String, FreezeOutcome>?,
    ): Map<String, FreezeOutcome> {
        val settled = mutableMapOf<String, FreezeOutcome>()
        var left = cut

        for (wave in 2..WAVES) {
            val checked = again(listOf(control) + left.sorted()) ?: break
            val step = afterWave(left, control, checked)

            if (step == null) {
                log("check $wave of $WAVES: $control that works here did not pass, the cut nodes are not marked")

                break
            }

            log("check $wave of $WAVES: ${left.size} cut node(s) — ${step.cut.size} cut again, ${step.passed.size} passed")

            settled += step.passed

            if (wave == WAVES) {
                settled += step.cut

                break
            }

            left = step.cut.keys

            if (left.isEmpty()) break
        }

        return settled
    }

    // Проверки захода — в записи сети, по отпечатку
    fun recorded(
        nodes: Map<String, FreezeNode>,
        fingerprints: Map<String, String>,
        outcomes: Map<String, FreezeOutcome>,
        now: Long,
    ): Map<String, FreezeNode> {
        val out = nodes.toMutableMap()

        for ((name, outcome) in outcomes) {
            val fingerprint = fingerprints[name] ?: continue

            out[fingerprint] = apply(out[fingerprint], outcome, now, name)
        }

        return out
    }

    // Выбросить забытые сети и отпечатки, которых в подписке больше нет
    fun prune(file: FreezeFile, now: Long, live: Set<String>): FreezeFile = file.copy(
        networks = file.networks
            .filterValues { now - it.lastSeen < FORGET_NETWORK_AFTER }
            .mapValues { (_, network) -> network.copy(nodes = network.nodes.filterKeys { it in live }) },
    )

    // Кого проверять: один узел на отпечаток, только просроченные
    fun due(fingerprints: Map<String, String>, network: FreezeNetwork, now: Long): List<String> {
        val taken = mutableSetOf<String>()

        return fingerprints.entries
            .sortedBy { it.key }
            .filter { taken.add(it.value) }
            .filter { isDue(network.nodes[it.value], now) }
            .map { it.key }
    }

    // Пометки по именам: все одноимённые отпечатку узлы получают его итог
    fun marks(fingerprints: Map<String, String>, network: FreezeNetwork): Map<String, String> =
        fingerprints.mapNotNull { (name, fingerprint) ->
            FreezeVerdict.mark(network.nodes[fingerprint]?.verdict)?.let { name to it }
        }.toMap()
}
