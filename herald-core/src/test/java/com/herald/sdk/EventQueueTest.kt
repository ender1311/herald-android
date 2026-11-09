package com.herald.sdk

import com.herald.sdk.internal.EventQueue
import com.herald.sdk.internal.QueuedEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EventQueueTest {
    private val dir: File = Files.createTempDirectory("queue").toFile()
    private val file = File(dir, "q.json")

    private fun event(n: Int, user: String? = null) = QueuedEvent(user, null, JSONObject().put("id", "e$n"))

    @Test
    fun survivesReinit() {
        EventQueue(file).apply { add(event(1, "u")); add(event(2, "u")) }
        val reloaded = EventQueue(file)
        assertEquals(2, reloaded.size)
        assertEquals(listOf("e1", "e2"), reloaded.peekBatch(10).map { it.event.getString("id") })
        assertEquals("u", reloaded.peekBatch(10).first().userId)
    }

    @Test
    fun capDropsOldestAndCounts() {
        val q = EventQueue(file, cap = 5)
        (1..8).forEach { q.add(event(it)) }
        assertEquals(5, q.size)
        assertEquals(3, q.dropped)
        assertEquals("e4", q.peekBatch(1).single().event.getString("id"))
        assertEquals(3, EventQueue(file, cap = 5).dropped)

        q.removeFirst(2, reportedDropped = 3)
        assertEquals(0, q.dropped)
        assertEquals(3, EventQueue(file, cap = 5).size)
    }

    @Test
    fun batchesNeverMixUsers() {
        val q = EventQueue(file)
        q.add(event(1, "a")); q.add(event(2, "a")); q.add(event(3, null)); q.add(event(4, "b"))
        assertEquals(2, q.peekBatch(100).size)
        q.removeFirst(2, 0)
        assertEquals(null, q.peekBatch(100).single().userId)
    }

    @Test
    fun corruptFileStartsEmpty() {
        file.writeText("{not json")
        assertEquals(0, EventQueue(file).size)
    }

    @Test
    fun clearDeletesFile() {
        val q = EventQueue(file)
        q.add(event(1))
        q.clear()
        assertEquals(0, EventQueue(file).size)
    }
}
