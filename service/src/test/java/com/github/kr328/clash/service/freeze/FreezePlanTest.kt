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
    fun `не прошло ничего — контрольный узел тот, что рабочий здесь свежее всех`() {
        val fingerprints = mapOf("new" to "f0", "old" to "f1", "fresh" to "f2", "cut" to "f3", "twin" to "f2")
        val network = FreezeNetwork(
            nodes = mapOf(
                "f1" to FreezeNode(verdict = "ok", at = 100),
                "f2" to FreezeNode(verdict = "ok", at = 200),
                "f3" to FreezeNode(verdict = "frozen", at = 300),
            ),
        )
        val nothing = mapOf("new" to FreezeOutcome("unknown", 0))

        assertEquals("fresh", FreezePlan.control(fingerprints, network, nothing))
        assertEquals("old", FreezePlan.control(fingerprints, network.copy(nodes = network.nodes - "f2"), nothing))
        assertEquals(null, FreezePlan.control(fingerprints, network, nothing + ("fresh" to FreezeOutcome("unknown", 0))))
        assertEquals(null, FreezePlan.control(fingerprints, network, mapOf("new" to FreezeOutcome("dead", 0), "cut" to FreezeOutcome("frozen", 200))))
        assertEquals(null, FreezePlan.control(fingerprints, FreezeNetwork(), nothing))
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
