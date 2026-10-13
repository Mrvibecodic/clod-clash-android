package com.github.kr328.clash.service.freeze

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezePlanTest {
    private val now = 1_800_000_000L

    private fun checked(verdict: String?, ago: Long) = FreezeNode(verdict = verdict, at = now - ago, tried = now - ago)

    @Test
    fun `узел без итога проверяется сразу, итоги ждут своего срока`() {
        assertTrue(FreezePlan.isDue(null, now))

        val cases = listOf(
            Triple("ok", FreezePlan.RECHECK_AFTER - 1, false),
            Triple("ok", FreezePlan.RECHECK_AFTER, true),
            Triple("frozen", FreezePlan.RECHECK_AFTER - 1, false),
            Triple("frozen", FreezePlan.RECHECK_AFTER, true),
            Triple("dead", FreezePlan.RETRY_AFTER - 1, false),
            Triple("dead", FreezePlan.RETRY_AFTER, true),
            Triple(null, FreezePlan.RETRY_AFTER - 1, false),
            Triple(null, FreezePlan.RETRY_AFTER, true),
        )

        for ((verdict, ago, due) in cases) {
            assertEquals("$verdict $ago", due, FreezePlan.isDue(checked(verdict, ago), now))
        }
    }

    @Test
    fun `неясно оставляет пометку и двигает только время попытки`() {
        val node = FreezePlan.apply(checked("frozen", FreezePlan.RECHECK_AFTER), FreezeOutcome("unknown", 429), now, "a")

        assertEquals("frozen", node.verdict)
        assertEquals(now - FreezePlan.RECHECK_AFTER, node.at)
        assertEquals(now, node.tried)
        assertEquals("a" to 429, node.name to node.status)
        assertFalse(FreezePlan.isDue(node, now + FreezePlan.RETRY_AFTER - 1))

        assertEquals(
            checked("ok", 0).copy(name = "a", status = 200),
            FreezePlan.apply(checked("dead", FreezePlan.RETRY_AFTER), FreezeOutcome("ok", 200), now, "a"),
        )
    }

    @Test
    fun `заход, где ни через кого ничего не прошло, не записывается`() {
        val dead = FreezeOutcome("dead", 0)
        val silent = FreezeOutcome("unknown", 0)
        val refused = FreezeOutcome("unknown", 403)

        assertFalse(FreezePlan.worthRecording(emptyList()))
        assertFalse(FreezePlan.worthRecording(listOf(dead, silent)))
        assertTrue(FreezePlan.worthRecording(listOf(dead, FreezeOutcome("frozen", 200))))
        assertTrue(FreezePlan.worthRecording(listOf(FreezeOutcome("ok", 200))))
        // Сайт ответил отказом — через узел прошло, повторять сразу незачем
        assertTrue(FreezePlan.worthRecording(listOf(dead, refused)))
    }

    @Test
    fun `один узел на отпечаток, только просроченные, пометки — всем одноимённым`() {
        val network = FreezeNetwork(
            lastSeen = now,
            nodes = mapOf("fresh" to checked("ok", 0), "f1" to checked("frozen", 0), "f3" to checked("dead", 0)),
        )
        val fingerprints = mapOf("a" to "new", "a2" to "new", "b" to "fresh", "c" to "other", "d" to "f1", "d2" to "f1", "e" to "f3")

        assertEquals(listOf("a", "c"), FreezePlan.due(fingerprints, network, now))
        assertEquals(mapOf("d" to "frozen", "d2" to "frozen", "e" to "dead"), FreezePlan.marks(fingerprints, network))
    }

    @Test
    fun `старые сети и пропавшие отпечатки забываются`() {
        val file = FreezeFile(
            networks = mapOf(
                "here" to FreezeNetwork(lastSeen = now, nodes = mapOf("live" to checked("frozen", 0), "gone" to checked("dead", 0))),
                "old" to FreezeNetwork(lastSeen = now - FreezePlan.FORGET_NETWORK_AFTER),
                "recent" to FreezeNetwork(lastSeen = now - FreezePlan.FORGET_NETWORK_AFTER + 1),
            ),
        )

        val pruned = FreezePlan.prune(file, now, setOf("live"))

        assertEquals(setOf("here", "recent"), pruned.networks.keys)
        assertEquals(setOf("live"), pruned.networks.getValue("here").nodes.keys)
    }

    @Test
    fun `неясно снимает не отвечает — узел ответил`() {
        val node = FreezePlan.apply(checked("dead", FreezePlan.RETRY_AFTER), FreezeOutcome("unknown", 403), now, "a")

        assertEquals(null, node.verdict)
        assertEquals(now, node.tried)
        assertEquals(emptyMap<String, String>(), FreezePlan.marks(mapOf("a" to "fp"), FreezeNetwork(nodes = mapOf("fp" to node))))
    }

    @Test
    fun `пометку ставят только итоги, которые повторяются`() {
        val outcomes = mapOf(
            "ok" to FreezeOutcome("ok", 200),
            "cut" to FreezeOutcome("frozen", 200),
            "dead" to FreezeOutcome("dead", 0),
            "refused" to FreezeOutcome("unknown", 403),
        )

        assertEquals(setOf("cut", "dead"), FreezePlan.negatives(outcomes))
    }

    @Test
    fun `контрольный — прошедший в заходе, иначе рабочий здесь свежее всех`() {
        val fingerprints = mapOf("new" to "f0", "old" to "f1", "fresh" to "f2", "cut" to "f3", "twin" to "f3", "b" to "f4", "a" to "f5")
        val network = FreezeNetwork(
            nodes = mapOf(
                "f1" to FreezeNode(verdict = "ok", at = 100),
                "f2" to FreezeNode(verdict = "ok", at = 200),
                "f3" to FreezeNode(verdict = "ok", at = 300),
            ),
        )
        val cut = mapOf("cut" to FreezeOutcome("frozen", 200))

        // Одноимённый отпечатку повторяемого не годится, хоть и свежее всех
        assertEquals("fresh", FreezePlan.control(fingerprints, network, cut, setOf("cut")))
        assertEquals("a", FreezePlan.control(fingerprints, network, cut + ("b" to FreezeOutcome("ok", 200)) + ("a" to FreezeOutcome("ok", 200)), setOf("cut")))
        // Проверенный в заходе без «работает» — не контрольный
        assertEquals("old", FreezePlan.control(fingerprints, network, cut + ("fresh" to FreezeOutcome("unknown", 0)), setOf("cut")))
        assertEquals(null, FreezePlan.control(fingerprints, FreezeNetwork(), cut, setOf("cut")))
    }

    @Test
    fun `итог даёт повтор, а не прошедший контрольный снимает повторяемые`() {
        val outcomes = mapOf(
            "ok" to FreezeOutcome("ok", 200),
            "cut" to FreezeOutcome("frozen", 200),
            "dead" to FreezeOutcome("dead", 0),
            "blip" to FreezeOutcome("frozen", 200),
        )
        val negatives = setOf("cut", "dead", "blip")
        val again = mapOf(
            "cut" to FreezeOutcome("frozen", 200),
            "dead" to FreezeOutcome("dead", 0),
            "blip" to FreezeOutcome("ok", 200),
            "ok" to FreezeOutcome("ok", 200),
        )

        assertEquals(
            mapOf("ok" to FreezeOutcome("ok", 200), "cut" to FreezeOutcome("frozen", 200), "dead" to FreezeOutcome("dead", 0), "blip" to FreezeOutcome("ok", 200)),
            FreezePlan.confirmed(outcomes, negatives, again, "ok"),
        )

        // Контрольный не прошёл или повтор оборван — повторяемых в итоге нет
        assertEquals(mapOf("ok" to FreezeOutcome("ok", 200)), FreezePlan.confirmed(outcomes, negatives, again + ("ok" to FreezeOutcome("dead", 0)), "ok"))
        assertEquals(mapOf("ok" to FreezeOutcome("ok", 200)), FreezePlan.confirmed(outcomes, negatives, again - "ok", "ok"))
        assertEquals(
            mapOf("ok" to FreezeOutcome("ok", 200), "cut" to FreezeOutcome("frozen", 200), "dead" to FreezeOutcome("dead", 0)),
            FreezePlan.confirmed(outcomes, negatives, again - "blip", "ok"),
        )

        // Без контрольного решает повтор; контрольный со стороны входит в итог, если пометки не ставит
        assertEquals(outcomes + ("blip" to FreezeOutcome("ok", 200)), FreezePlan.confirmed(outcomes, negatives, again, null))
        val alone = mapOf("dead" to FreezeOutcome("dead", 0))
        assertEquals(
            mapOf("dead" to FreezeOutcome("dead", 0), "old" to FreezeOutcome("ok", 200)),
            FreezePlan.confirmed(alone, setOf("dead"), mapOf("dead" to FreezeOutcome("dead", 0), "old" to FreezeOutcome("ok", 200)), "old"),
        )
        assertEquals(
            mapOf("dead" to FreezeOutcome("dead", 0)),
            FreezePlan.confirmed(alone, setOf("dead"), mapOf("dead" to FreezeOutcome("dead", 0), "old" to FreezeOutcome("frozen", 200)), "old"),
        )
    }

    @Test
    fun `файл в той же схеме, что на ПК, и терпит чужие поля`() {
        val json = Json { ignoreUnknownKeys = true }
        val file = FreezeFile(networks = mapOf("k" to FreezeNetwork(lastSeen = now, nodes = mapOf("fp" to checked("dead", 0)))))
        val text = json.encodeToString(FreezeFile.serializer(), file)

        assertTrue(text, text.contains("\"last_seen\"") && text.contains("\"dead\""))
        assertEquals(file, json.decodeFromString(FreezeFile.serializer(), text))

        val tolerant = json.decodeFromString(
            FreezeFile.serializer(),
            """{"networks":{"k":{"last_seen":1,"nodes":{"fp":{"tried":5}}}},"extra":1}""",
        )

        assertEquals(FreezeNode(verdict = null, at = 0, tried = 5), tolerant.networks.getValue("k").nodes.getValue("fp"))
    }
}
