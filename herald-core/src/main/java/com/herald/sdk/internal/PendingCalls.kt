package com.herald.sdk.internal

/**
 * Public calls made before `Herald.configure`, kept in call order and replayed once the SDK starts
 * (as on iOS). Bounded: past [capacity] further calls are dropped with one warning.
 */
internal class PendingCalls<T>(private val capacity: Int = CAPACITY) {
    private var calls: MutableList<T>? = mutableListOf()
    private var warned = false

    /** Buffers [call] and returns true while not started; returns false once started (run it directly). */
    @Synchronized
    fun offer(call: T): Boolean {
        val buffer = calls ?: return false
        if (buffer.size < capacity) {
            buffer.add(call)
        } else if (!warned) {
            warned = true
            HeraldLog.warn("More than $capacity calls before Herald.configure; dropping later ones until it runs")
        }
        return true
    }

    /**
     * Hands every buffered call to [run], in order, then switches to pass-through. Holds the lock while
     * replaying, so a call made concurrently runs after the replayed ones.
     */
    @Synchronized
    fun start(run: (T) -> Unit) {
        val buffer = calls ?: return
        calls = null
        buffer.forEach(run)
    }

    companion object {
        const val CAPACITY = 1000
    }
}
