package com.github.kr328.clash.service

import com.github.kr328.clash.service.ProfileProcessor.ageMigrationState
import com.github.kr328.clash.service.ProfileProcessor.readMigration
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class MigrationStateAgingTest {
    private val now = TimeUnit.DAYS.toMillis(1000)

    private fun state(text: String) = ageMigrationState(readMigration(file(text), now), now)

    private fun file(text: String): File {
        val file = File.createTempFile("migration", ".json")

        file.deleteOnExit()
        file.writeText(text)

        return file
    }

    @Test
    fun legacyFormatLetsTheCounterAgeOut() {
        assertEquals(0, state("""{"hops":3,"previous":["https://old/sub"]}""").hops)
    }

    @Test
    fun legacyFormatKeepsTheLeftBehindAddress() {
        assertEquals(
            listOf("https://old/sub"),
            state("""{"hops":3,"previous":["https://old/sub"]}""").history.map { it.url },
        )
    }

    @Test
    fun currentFormatKeepsAFreshCounter() {
        val fresh = now - TimeUnit.HOURS.toMillis(1)

        assertEquals(3, state("""{"hops":3,"history":[],"lastAt":$fresh}""").hops)
    }

    @Test
    fun currentFormatLetsAnOldCounterAgeOut() {
        val old = now - TimeUnit.DAYS.toMillis(2)

        assertEquals(0, state("""{"hops":3,"history":[],"lastAt":$old}""").hops)
    }

    @Test
    fun missingFileIsAnEmptyState() {
        val absent = File.createTempFile("migration", ".json").also { it.delete() }

        assertEquals(0, ageMigrationState(readMigration(absent, now), now).hops)
    }
}
