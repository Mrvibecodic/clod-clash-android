package com.github.kr328.clash.service.freeze

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezePlanTest {
    private val now = 1_800_000_000L

    private val ok = FreezeOutcome("ok", 200)
    private val cut = FreezeOutcome("frozen", 200)
    private val silent = FreezeOutcome("dead", 0)

    private fun checked(verdict: String?, ago: Long) = FreezeNode(verdict = verdict, at = now - ago, tried = now - ago)

    @Test
    fun `узел без итога проверяется сразу, итоги ждут своего срока`() {
        assertTrue(FreezePlan.isDue(null, now))

        val cases = listOf(
            Triple("ok", FreezePlan.RECHECK_AFTER - 1, false),
            Triple("ok", FreezePlan.RECHECK_AFTER, true),
            Triple("frozen", FreezePlan.RECHECK_AFTER - 1, false),
            Triple("frozen", FreezePlan.RECHECK_AFTER, true),
            Triple(null, FreezePlan.RETRY_AFTER - 1, false),
            Triple(null, FreezePlan.RETRY_AFTER, true),
        )

        for ((verdict, ago, due) in cases) {
            assertEquals("$verdict $ago", due, FreezePlan.isDue(checked(verdict, ago), now))
        }
    }

    @Test
    fun `проверка без итога оставляет итог и двигает только время попытки — повтор через 6 часов`() {
        val node = FreezePlan.apply(checked("frozen", FreezePlan.RECHECK_AFTER), FreezeOutcome("unknown", 429), now, "a")

        assertEquals("frozen", node.verdict)
        assertEquals(now - FreezePlan.RECHECK_AFTER, node.at)
        assertEquals(now, node.tried)
        assertEquals("a" to 429, node.name to node.status)
        assertFalse(FreezePlan.isDue(node, now + FreezePlan.RETRY_AFTER - 1))
        assertTrue("итогу 3 суток — повтор не ждёт ещё 3", FreezePlan.isDue(node, now + FreezePlan.RETRY_AFTER))

        assertEquals(
            checked("ok", 0).copy(name = "a", status = 200),
            FreezePlan.apply(checked(null, FreezePlan.RETRY_AFTER), ok, now, "a"),
        )
    }

    @Test
    fun `итог — только работает или режется`() {
        assertTrue(FreezeVerdict.settled("ok") && FreezeVerdict.settled("frozen"))

        for (word in listOf("dead", "unknown", "", null)) {
            assertFalse("$word", FreezeVerdict.settled(word))
            assertEquals(null, FreezeVerdict.mark(word))
        }

        assertEquals(null, FreezeVerdict.mark("ok"))
        assertEquals("frozen", FreezeVerdict.mark("frozen"))
    }

    @Test
    fun `один узел на отпечаток, только просроченные, пометки — всем одноимённым`() {
        val network = FreezeNetwork(
            lastSeen = now,
            nodes = mapOf("fresh" to checked("ok", 0), "f1" to checked("frozen", 0)),
        )
        val fingerprints = mapOf("a" to "new", "a2" to "new", "b" to "fresh", "c" to "other", "d" to "f1", "d2" to "f1")

        assertEquals(listOf("a", "c"), FreezePlan.due(fingerprints, network, now))
        assertEquals(mapOf("d" to "frozen", "d2" to "frozen"), FreezePlan.marks(fingerprints, network))
    }

    @Test
    fun `старые сети и пропавшие отпечатки забываются`() {
        val file = FreezeFile(
            networks = mapOf(
                "here" to FreezeNetwork(lastSeen = now, nodes = mapOf("live" to checked("frozen", 0), "gone" to checked("ok", 0))),
                "old" to FreezeNetwork(lastSeen = now - FreezePlan.FORGET_NETWORK_AFTER),
                "recent" to FreezeNetwork(lastSeen = now - FreezePlan.FORGET_NETWORK_AFTER + 1),
            ),
        )

        val pruned = FreezePlan.prune(file, now, setOf("live"))

        assertEquals(setOf("here", "recent"), pruned.networks.keys)
        assertEquals(setOf("live"), pruned.networks.getValue("here").nodes.keys)
    }

    @Test
    fun `повторяют только обрезанных, и до повторов итога у них нет`() {
        val outcomes = mapOf("ok" to ok, "cut" to cut, "dead" to silent, "refused" to FreezeOutcome("unknown", 403))

        assertEquals(setOf("cut"), FreezePlan.suspects(outcomes))
        assertEquals(outcomes + ("cut" to FreezeOutcome("", 200)), FreezePlan.unconfirmed(outcomes))
    }

    @Test
    fun `контрольный — прошедший в заходе, иначе рабочий здесь свежее всех с итогом последней попытки`() {
        val fingerprints = mapOf("new" to "f0", "old" to "f1", "fresh" to "f2", "cut" to "f3", "twin" to "f3", "b" to "f4", "a" to "f5", "stale" to "f6")
        val network = FreezeNetwork(
            nodes = mapOf(
                "f1" to FreezeNode(verdict = "ok", at = 100, tried = 100),
                "f2" to FreezeNode(verdict = "ok", at = 200, tried = 200),
                "f3" to FreezeNode(verdict = "ok", at = 300, tried = 300),
                // Рабочий, но последняя попытка итога не дала — не контрольный
                "f6" to FreezeNode(verdict = "ok", at = 250, tried = 260),
            ),
        )
        val first = mapOf("cut" to FreezeOutcome("", 200))

        // Одноимённый отпечатку повторяемого не годится, хоть и свежее всех
        assertEquals("fresh", FreezePlan.control(fingerprints, network, first, setOf("cut")))
        assertEquals("a", FreezePlan.control(fingerprints, network, first + ("b" to ok) + ("a" to ok), setOf("cut")))
        // Проверенный в заходе без «работает» — не контрольный, и его двойник тоже
        assertEquals("old", FreezePlan.control(fingerprints, network, first + ("fresh" to silent), setOf("cut")))
        assertEquals("old", FreezePlan.control(fingerprints + ("fresh2" to "f2"), network, first + ("fresh" to silent), setOf("cut")))
        assertEquals(null, FreezePlan.control(fingerprints, FreezeNetwork(), first, setOf("cut")))
    }

    @Test
    fun `повтор раскладывает узлы на снова обрезанные и прошедшие, без контрольного он не в счёт`() {
        assertEquals(3, FreezePlan.WAVES)

        // Вторая проверка: a снова обрезан, b прошёл, d без итога — выбывает без итога
        val second = FreezePlan.afterWave(setOf("a", "b", "d"), "c", mapOf("a" to cut, "b" to ok, "d" to silent, "c" to ok))

        assertEquals(FreezeWave(cut = mapOf("a" to cut), passed = mapOf("b" to ok)), second)

        // Третья: a обрезан в третий раз — его итог «режется»
        val third = FreezePlan.afterWave(setOf("a"), "c", mapOf("a" to cut, "c" to ok))

        assertEquals(FreezeWave(cut = mapOf("a" to cut), passed = emptyMap()), third)

        // Контрольный не прошёл, сам обрезался или повтор оборвался — это сеть, повтор не в счёт
        assertEquals(null, FreezePlan.afterWave(setOf("a"), "c", mapOf("a" to cut, "c" to silent)))
        assertEquals(null, FreezePlan.afterWave(setOf("a"), "c", mapOf("a" to cut, "c" to cut)))
        assertEquals(null, FreezePlan.afterWave(setOf("a"), "c", emptyMap()))
    }

    // Повторы на заготовленных ответах ядра: каждый ответ — одна проверка
    private fun repeatedOn(answers: List<Map<String, FreezeOutcome>?>): Map<String, FreezeOutcome> = runBlocking {
        val left = answers.iterator()
        var asked = 0

        val settled = FreezePlan.repeated(setOf("a"), "k", {}) { batch ->
            asked += 1

            assertEquals("контрольный проверяется в каждом повторе", "k", batch.first())

            if (left.hasNext()) left.next() else null
        }

        assertTrue("повторов не больше двух", asked <= FreezePlan.WAVES - 1)

        settled
    }

    @Test
    fun `режется — только если обрезались все три проверки подряд, а контрольный прошёл оба раза`() {
        fun wave(a: FreezeOutcome, k: FreezeOutcome) = mapOf("k" to k, "a" to a)

        val cases = listOf(
            Triple("обрезан трижды, контрольный прошёл", listOf(wave(cut, ok), wave(cut, ok)), "frozen"),
            Triple("последняя прошла", listOf(wave(cut, ok), wave(ok, ok)), "ok"),
            Triple("прошёл во втором", listOf(wave(ok, ok)), "ok"),
            Triple("последняя без итога", listOf(wave(cut, ok), wave(silent, ok)), null),
            Triple("контрольный не прошёл во втором", listOf(wave(cut, silent)), null),
            Triple("контрольный обрезан в третьем", listOf(wave(cut, ok), wave(cut, cut)), null),
            Triple("повтор оборван", listOf(wave(cut, ok), null), null),
        )

        for ((name, answers, expected) in cases) {
            assertEquals(name, expected, repeatedOn(answers)["a"]?.verdict)
        }
    }

    @Test
    fun `проверка без итога записывается только как попытка`() {
        val nodes = mapOf("fa" to checked("frozen", FreezePlan.RECHECK_AFTER))
        val fingerprints = mapOf("a" to "fa", "b" to "fb")
        val recorded = FreezePlan.recorded(nodes, fingerprints, mapOf("a" to FreezeOutcome("", 200), "b" to ok), now)

        assertEquals("frozen", recorded.getValue("fa").verdict)
        assertEquals(now, recorded.getValue("fa").tried)
        assertFalse(FreezePlan.isDue(recorded.getValue("fa"), now))
        assertEquals("ok", recorded.getValue("fb").verdict)
    }

    @Test
    fun `файл в той же схеме, что на ПК, и терпит чужие поля`() {
        val json = Json { ignoreUnknownKeys = true }
        val file = FreezeFile(networks = mapOf("k" to FreezeNetwork(lastSeen = now, nodes = mapOf("fp" to checked("frozen", 0)))))
        val text = json.encodeToString(FreezeFile.serializer(), file)

        assertTrue(text, text.contains("\"last_seen\"") && text.contains("\"frozen\""))
        assertEquals(file, json.decodeFromString(FreezeFile.serializer(), text))

        val tolerant = json.decodeFromString(
            FreezeFile.serializer(),
            """{"networks":{"k":{"last_seen":1,"nodes":{"fp":{"tried":5}}}},"extra":1}""",
        )

        assertEquals(FreezeNode(verdict = null, at = 0, tried = 5), tolerant.networks.getValue("k").nodes.getValue("fp"))
    }
}
