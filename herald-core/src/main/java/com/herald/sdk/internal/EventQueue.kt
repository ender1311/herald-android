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

    /** Oldest run of events recorded under one user, at most [max], skipping the users in [held]. */
    fun peekBatch(max: Int, held: Set<String> = emptySet()): List<QueuedEvent> {
        val sendable = entries.asSequence().filter { it.userId == null || it.userId !in held }
        val first = sendable.firstOrNull() ?: return emptyList()
        return sendable.takeWhile { it.userId == first.userId }.take(max).toList()
    }

    /** Removes [batch] (events from [peekBatch]) and subtracts the dropped count the batch reported. */
    fun remove(batch: List<QueuedEvent>, reportedDropped: Int) {
        entries.removeAll { e -> batch.any { it === e } }
        dropped = (dropped - reportedDropped).coerceAtLeast(0)
        persist()
    }

    /** Gives [userId]'s queued events that have no token the one set later. */
    fun assignToken(userId: String, token: String) {
        var changed = false
        entries.replaceAll { e ->
            if (e.userId == userId && e.userToken == null) e.copy(userToken = token).also { changed = true } else e
        }
        if (changed) persist()
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
