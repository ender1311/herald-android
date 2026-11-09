package com.herald.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A queued event plus the identity it was recorded under (not part of the wire event). */
internal data class QueuedEvent(val userId: String?, val userToken: String?, val event: JSONObject)

/**
 * Disk-backed FIFO of events. Survives process death; the oldest events are dropped past [cap]
 * and counted in [dropped] until a batch carrying that count is accepted.
 */
internal class EventQueue(private val file: File, private val cap: Int = 1000) {
    private val entries = ArrayDeque<QueuedEvent>()
    var dropped: Int = 0
        private set

    val size: Int get() = entries.size

    init {
        load()
    }

    fun add(entry: QueuedEvent) {
        entries.addLast(entry)
        while (entries.size > cap) {
            entries.removeFirst()
            dropped++
        }
        persist()
    }

    /** Leading run of events recorded under one user, at most [max]. */
    fun peekBatch(max: Int): List<QueuedEvent> {
        val first = entries.firstOrNull() ?: return emptyList()
        return entries.asSequence().take(max).takeWhile { it.userId == first.userId }.toList()
    }

    /** Removes the first [count] events and subtracts the dropped count the batch reported. */
    fun removeFirst(count: Int, reportedDropped: Int) {
        repeat(minOf(count, entries.size)) { entries.removeFirst() }
        dropped = (dropped - reportedDropped).coerceAtLeast(0)
        persist()
    }

    fun clear() {
        entries.clear()
        dropped = 0
        file.delete()
    }

    // shortcut: rewrites the whole file per change (<= 1000 small events); switch to append-only if profiling shows cost.
    private fun persist() {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("u", e.userId ?: JSONObject.NULL)
                    .put("t", e.userToken ?: JSONObject.NULL)
                    .put("e", e.event),
            )
        }
        try {
            file.writeAtomically(JSONObject().put("dropped", dropped).put("entries", arr).toString())
        } catch (e: Exception) {
            HeraldLog.error("Could not persist event queue", e)
        }
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            dropped = root.num("dropped")?.toInt() ?: 0
            val arr = root.arr("entries") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val event = o.obj("e") ?: continue
                entries.addLast(QueuedEvent(o.str("u"), o.str("t"), event))
            }
        } catch (e: Exception) {
            HeraldLog.error("Event queue file unreadable; starting empty", e)
            entries.clear()
        }
    }
}
