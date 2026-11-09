package com.herald.sdk.internal

import com.herald.sdk.FilterOperator
import com.herald.sdk.InAppMessage
import com.herald.sdk.PropertyFilter
import com.herald.sdk.ReEligibilityMode
import com.herald.sdk.TriggerType

internal data class TriggerEvent(
    val type: TriggerType,
    val name: String? = null,
    val properties: Map<String, Any?> = emptyMap(),
)

/**
 * Picks the in-app message for a trigger: matching trigger and filters, unexpired, re-eligible,
 * highest priority (first wins a tie), at least [minIntervalMs] after the last triggered display,
 * and never while another Herald message is on screen.
 */
internal class InAppEngine(
    private val clock: () -> Long,
    private val minIntervalMs: Long,
    /** "userKey|messageId" -> epoch ms last displayed; persisted by the owner, cleared only by wipeData. */
    val lastShown: MutableMap<String, Long> = mutableMapOf(),
    /** The current user id, or "anonymous". Display history is per user. */
    private val userKey: () -> String = { ANONYMOUS },
) {
    var messages: List<InAppMessage> = emptyList()
    var onScreen: InAppMessage? = null
        private set
    private var lastDisplayAt: Long? = null

    fun evaluate(trigger: TriggerEvent): InAppMessage? {
        if (onScreen != null) return null
        val now = clock()
        if (!intervalElapsed()) return null
        return messages
            .filter { matches(it, trigger) && isLive(it, now) && isEligible(it, now) }
            .maxByOrNull { it.priority }
    }

    /** Whether the min interval since the last confirmed display has passed. */
    fun intervalElapsed(): Boolean = lastDisplayAt?.let { clock() - it >= minIntervalMs } ?: true

    /** Reserves the screen while a presenter is asked to show [message]; blocks other messages. */
    fun beginPresenting(message: InAppMessage) {
        onScreen = message
        // Captured now: a user switch before the presenter confirms must not write into the new user's history.
        presentingUserKey = userKey()
    }

    private var presentingUserKey: String? = null

    /** What [recordShown] replaced, so [revertShown] can undo a display the activity never let the user finish. */
    private var undo: Triple<String, Long?, Long?>? = null

    /** The presenter confirmed the message is visible: spend re-eligibility and start the interval. */
    fun recordShown(message: InAppMessage) {
        val now = clock()
        val key = "${presentingUserKey ?: userKey()}|${message.id}"
        undo = Triple(key, lastShown[key], lastDisplayAt)
        lastDisplayAt = now
        lastShown[key] = now
    }

    /** Undoes the last [recordShown] for [message]: eligibility and the interval are as before it showed. */
    fun revertShown(message: InAppMessage) {
        val (key, previousShown, previousDisplayAt) = undo ?: return
        if (!key.endsWith("|${message.id}")) return
        if (previousShown == null) lastShown.remove(key) else lastShown[key] = previousShown
        lastDisplayAt = previousDisplayAt
        undo = null
    }

    fun markDismissed() {
        onScreen = null
    }

    fun isEligible(message: InAppMessage): Boolean = isEligible(message, clock())

    private fun historyKey(messageId: String) = "${userKey()}|$messageId"

    fun isLive(message: InAppMessage, now: Long = clock()): Boolean =
        message.expiresAt == null || message.expiresAt > now

    private fun isEligible(message: InAppMessage, now: Long): Boolean {
        val shownAt = lastShown[historyKey(message.id)] ?: return true
        return when (message.reEligibility.mode) {
            ReEligibilityMode.ONCE -> false
            ReEligibilityMode.AFTER_HOURS -> now - shownAt >= ((message.reEligibility.hours ?: 0.0) * 3_600_000).toLong()
        }
    }

    private fun matches(message: InAppMessage, trigger: TriggerEvent): Boolean {
        val t = message.trigger
        if (t.type != trigger.type) return false
        if (t.type == TriggerType.EVENT && t.event != trigger.name) return false
        return t.propertyFilters.all { filterMatches(it, trigger.properties) }
    }

    companion object {
        const val ANONYMOUS = "anonymous"

        /**
         * Shared rule with iOS: a property that is missing from the event (absent or null) makes every
         * operator false, `exists` included; `exists` is true only when the property is present, and its
         * filter value is ignored.
         */
        fun filterMatches(filter: PropertyFilter, properties: Map<String, Any?>): Boolean {
            val actual = properties[filter.key] ?: return false
            val expected = filter.value
            return when (filter.op) {
                FilterOperator.EXISTS -> true
                FilterOperator.EQ -> valuesEqual(actual, expected)
                FilterOperator.NEQ -> !valuesEqual(actual, expected)
                FilterOperator.GT -> compare(actual, expected)?.let { it > 0 } ?: false
                FilterOperator.GTE -> compare(actual, expected)?.let { it >= 0 } ?: false
                FilterOperator.LT -> compare(actual, expected)?.let { it < 0 } ?: false
                FilterOperator.LTE -> compare(actual, expected)?.let { it <= 0 } ?: false
                FilterOperator.CONTAINS -> when (actual) {
                    is String -> expected is String && actual.contains(expected)
                    is Collection<*> -> actual.any { valuesEqual(it, expected) }
                    is Array<*> -> actual.any { valuesEqual(it, expected) }
                    else -> false
                }
            }
        }

        private fun valuesEqual(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

        private fun compare(a: Any?, b: Any?): Int? =
            if (a is Number && b is Number) a.toDouble().compareTo(b.toDouble()) else null
    }
}
