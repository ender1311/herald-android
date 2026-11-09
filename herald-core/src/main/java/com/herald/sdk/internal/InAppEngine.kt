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
    /** messageId -> epoch ms last displayed; persisted by the owner. */
    val lastShown: MutableMap<String, Long> = mutableMapOf(),
) {
    var messages: List<InAppMessage> = emptyList()
    var onScreen: InAppMessage? = null
        private set
    private var lastDisplayAt: Long? = null

    fun evaluate(trigger: TriggerEvent): InAppMessage? {
        if (onScreen != null) return null
        val now = clock()
        lastDisplayAt?.let { if (now - it < minIntervalMs) return null }
        return messages
            .filter { matches(it, trigger) && isLive(it, now) && isEligible(it, now) }
            .maxByOrNull { it.priority }
    }

    fun markDisplayed(message: InAppMessage) {
        val now = clock()
        onScreen = message
        lastDisplayAt = now
        lastShown[message.id] = now
    }

    fun markDismissed() {
        onScreen = null
    }

    fun isLive(message: InAppMessage, now: Long = clock()): Boolean =
        message.expiresAt == null || message.expiresAt > now

    private fun isEligible(message: InAppMessage, now: Long): Boolean {
        val shownAt = lastShown[message.id] ?: return true
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
        fun filterMatches(filter: PropertyFilter, properties: Map<String, Any?>): Boolean {
            val present = properties.containsKey(filter.key) && properties[filter.key] != null
            val actual = properties[filter.key]
            val expected = filter.value
            return when (filter.op) {
                FilterOperator.EXISTS -> present == ((expected as? Boolean) ?: true)
                FilterOperator.EQ -> present && valuesEqual(actual, expected)
                FilterOperator.NEQ -> !present || !valuesEqual(actual, expected)
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
