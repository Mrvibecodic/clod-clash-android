package com.github.kr328.clash.service.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class SelectionsQueueTest {
    @Test
    fun tasksRunInSubmissionOrder() = runBlocking {
        val writer = CoroutineScope(SupervisorJob() + Selections.queue)

        val order = Collections.synchronizedList(ArrayList<Int>())

        for (i in 0 until 100) {
            writer.launch { order += i }
        }

        val snapshot = withContext(Selections.queue) { order.toList() }

        assertEquals((0 until 100).toList(), snapshot)
    }

    @Test
    fun readAfterWriteSeesIt() = runBlocking {
        val writer = CoroutineScope(SupervisorJob() + Selections.queue)

        val stored = Collections.synchronizedList(ArrayList<String>())

        writer.launch { stored += "выбор" }

        val seen = withContext(Selections.queue) { stored.toList() }

        assertEquals(listOf("выбор"), seen)
    }
}
