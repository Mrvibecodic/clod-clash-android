package com.github.kr328.clash.service.freeze

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// clod:freeze — чистые решения проверки 16–20: кого проверять и что записать.
// Схема файла та же, что на ПК: сеть → отпечаток узла → итог.

@Serializable
data class FreezeNode(
    // ok / frozen / dead; null — вердикта ещё не было
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
    val networks: Map<String, FreezeNetwork> = emptyMap(),
)

// Ответ ядра на одну проверку: вердикт и код ответа сайта (0 — ответа не было)
@Serializable
data class FreezeOutcome(
    val verdict: String = "unknown",
    val status: Int = 0,
) {
    // Через узел что-то прошло: сайт ответил, пусть и не тем, или трафик шёл
    val answered: Boolean
        get() = verdict == FreezeVerdict.OK || verdict == FreezeVerdict.FROZEN || status != 0
}

object FreezeVerdict {
    const val OK = "ok"
    const val FROZEN = "frozen"
    const val DEAD = "dead"

    // Итог, который показывается пометкой; «работает» пометкой не бывает
    fun mark(verdict: String?): String? = verdict?.takeIf { it == FROZEN || it == DEAD }

    fun settled(verdict: String?): Boolean = verdict == OK || verdict == FROZEN || verdict == DEAD
}

object FreezePlan {
    // «Работает» и «режется» — повтор не чаще раза в 3 суток
    const val RECHECK_AFTER = 3L * 24 * 60 * 60

    // «Не отвечает» и попытка без итога — повтор через 6 часов
    const val RETRY_AFTER = 6L * 60 * 60

    // Сеть, где клиент не был столько, забывается
    const val FORGET_NETWORK_AFTER = 30L * 24 * 60 * 60

    // «Были в этой сети» обновляется в файле не чаще раза в сутки
    const val TOUCH_AFTER = 24L * 60 * 60

    fun isDue(node: FreezeNode?, now: Long): Boolean {
        if (node == null) return true

        val sinceTry = now - node.tried

        return when (node.verdict) {
            FreezeVerdict.OK, FreezeVerdict.FROZEN -> sinceTry >= RECHECK_AFTER
            else -> sinceTry >= RETRY_AFTER
        }
    }

    // Вердикт — целиком, «неясно» — время попытки и снятие «не отвечает»: узел
    // ответил; имя и код ответа — всегда
    fun apply(node: FreezeNode?, outcome: FreezeOutcome, now: Long, name: String? = null): FreezeNode {
        val previous = node ?: FreezeNode()

        return if (FreezeVerdict.settled(outcome.verdict)) {
            FreezeNode(verdict = outcome.verdict, at = now, tried = now, name = name, status = outcome.status)
        } else {
            previous.copy(
                verdict = previous.verdict.takeUnless { it == FreezeVerdict.DEAD },
                tried = now,
                name = name ?: previous.name,
                status = outcome.status,
            )
        }
    }

    // Заход записывается, только если через кого-то что-то прошло: когда не
    // прошло ничего нигде, это скорее сеть, чем серверы
    fun worthRecording(outcomes: Collection<FreezeOutcome>): Boolean = outcomes.any { it.answered }

    // Итоги, которые ставят пометку, — только повторённые: одна пауза в сети
    // (метро, лифт) иначе повесила бы «режется» на 3 суток
    fun negatives(outcomes: Map<String, FreezeOutcome>): Set<String> =
        outcomes.filterValues { FreezeVerdict.mark(it.verdict) != null }.keys

    // Контрольный узел к повтору — через него в этой сети трафик идёт: прошёл в
    // этом заходе, иначе отмечен здесь рабочим (свежее всех) и не проверялся.
    // Повторяемые и одноимённые их отпечатку не годятся. Нет такого — повтор
    // без контроля
    fun control(
        fingerprints: Map<String, String>,
        network: FreezeNetwork,
        outcomes: Map<String, FreezeOutcome>,
        negatives: Set<String>,
    ): String? {
        val doubted = negatives.mapNotNull { fingerprints[it] }.toSet()
        val usable = fingerprints.filter { (name, fingerprint) -> name !in negatives && fingerprint !in doubted }

        usable.keys.filter { outcomes[it]?.verdict == FreezeVerdict.OK }.minOrNull()?.let { return it }

        return usable.entries
            .filter { it.key !in outcomes }
            .mapNotNull { (name, fingerprint) ->
                network.nodes[fingerprint]?.takeIf { it.verdict == FreezeVerdict.OK }?.let { name to it.at }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    // Итог захода после повтора: контрольный не прошёл — сеть под вопросом,
    // повторяемые в итог не входят и остаются в очереди; иначе их итог — повтор.
    // Оборванный повтор — тоже не в итог. Контрольный входит, только если
    // в заходе его не было и пометки он не ставит
    fun confirmed(
        outcomes: Map<String, FreezeOutcome>,
        negatives: Set<String>,
        again: Map<String, FreezeOutcome>,
        control: String?,
    ): Map<String, FreezeOutcome> {
        val settled = outcomes - negatives

        if (control != null && again[control]?.answered != true) return settled

        val repeated = negatives.mapNotNull { name -> again[name]?.let { name to it } }
        val checked = control
            ?.takeIf { it !in outcomes }
            ?.let { name -> again[name]?.takeIf { FreezeVerdict.mark(it.verdict) == null }?.let { name to it } }

        return settled + repeated + listOfNotNull(checked)
    }

    // Выбросить забытые сети и отпечатки, которых в подписке больше нет
    fun prune(file: FreezeFile, now: Long, live: Set<String>): FreezeFile = FreezeFile(
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
