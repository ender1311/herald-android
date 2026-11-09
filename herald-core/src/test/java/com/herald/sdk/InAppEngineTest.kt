package com.herald.sdk

import com.herald.sdk.internal.InAppEngine
import com.herald.sdk.internal.MessageParser
import com.herald.sdk.internal.TriggerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppEngineTest {
    private val clock = TestClock()
    private val fixtureMessages = MessageParser.parse(fixture("messages.json"))!!.inAppMessages
    private val modal = fixtureMessages.first { it.id == "msg_2" }
    private var user = "u_1"
    private val engine = InAppEngine({ clock.now }, 30_000, userKey = { user }).also { it.messages = fixtureMessages }

    private fun InAppEngine.show(message: InAppMessage) {
        beginPresenting(message)
        recordShown(message)
    }
    private val workout = TriggerEvent(TriggerType.EVENT, "workout_completed", mapOf("minutes" to 32))

    @Test
    fun matchesTriggerTypeEventNameAndFilters() {
        assertEquals("msg_2", engine.evaluate(workout)?.id)
        assertNull(engine.evaluate(workout.copy(properties = mapOf("minutes" to 10))))
        assertNull(engine.evaluate(workout.copy(name = "other")))
        assertEquals("msg_5", engine.evaluate(TriggerEvent(TriggerType.SESSION_START))?.id)
        assertEquals("msg_6", engine.evaluate(TriggerEvent(TriggerType.PUSH_OPEN))?.id)
    }

    @Test
    fun highestPriorityWinsAndTiesKeepServerOrder() {
        val low = modal.copy(id = "low", priority = 1)
        val high = modal.copy(id = "high", priority = 50)
        val tie = modal.copy(id = "tie", priority = 50)
        engine.messages = listOf(low, high, tie)
        assertEquals("high", engine.evaluate(workout)?.id)
    }

    @Test
    fun minimumIntervalBetweenDisplays() {
        engine.show(engine.evaluate(TriggerEvent(TriggerType.SESSION_START))!!)
        engine.markDismissed()
        clock.advance(29_000)
        assertNull(engine.evaluate(workout))
        clock.advance(1_000)
        assertEquals("msg_2", engine.evaluate(workout)?.id)
    }

    @Test
    fun neverOverAnotherMessage() {
        engine.show(engine.evaluate(TriggerEvent(TriggerType.SESSION_START))!!)
        clock.advance(60_000)
        assertNull(engine.evaluate(workout))
        engine.markDismissed()
        assertEquals("msg_2", engine.evaluate(workout)?.id)
    }

    @Test
    fun onceAndAfterHoursReEligibility() {
        engine.show(engine.evaluate(workout)!!)
        engine.markDismissed()
        clock.advance(48 * 3_600_000L)
        assertNull(engine.evaluate(workout))
        user = "u_2" // history is per user
        assertEquals("msg_2", engine.evaluate(workout)?.id)
        user = "u_1"

        val session = TriggerEvent(TriggerType.SESSION_START)
        engine.show(engine.evaluate(session)!!)
        engine.markDismissed()
        clock.advance(23 * 3_600_000L)
        assertNull(engine.evaluate(session))
        clock.advance(3_600_000L)
        assertEquals("msg_5", engine.evaluate(session)?.id)
    }

    @Test
    fun expiredMessagesNeverMatch() {
        engine.messages = listOf(modal.copy(expiresAt = clock.now - 1))
        assertNull(engine.evaluate(workout))
    }

    @Test
    fun filterOperators() {
        fun m(op: FilterOperator, value: Any?, props: Map<String, Any?>) =
            InAppEngine.filterMatches(PropertyFilter("k", op, value), props)
        assertTrue(m(FilterOperator.EQ, 3, mapOf("k" to 3.0)))
        assertTrue(m(FilterOperator.NEQ, "a", mapOf("k" to "b")))
        assertFalse(m(FilterOperator.NEQ, "a", mapOf("k" to "a")))
        assertTrue(m(FilterOperator.GTE, 3, mapOf("k" to 3)))
        assertTrue(m(FilterOperator.LT, 3, mapOf("k" to 2)))
        assertFalse(m(FilterOperator.LTE, 3, mapOf("k" to "2")))
        assertTrue(m(FilterOperator.CONTAINS, "ell", mapOf("k" to "hello")))
        assertTrue(m(FilterOperator.CONTAINS, "x", mapOf("k" to listOf("x", "y"))))
        assertTrue(m(FilterOperator.EXISTS, null, mapOf("k" to 0)))
        assertFalse(m(FilterOperator.EXISTS, true, mapOf("k" to null)))
        // Missing property (absent or null): every operator is false, exists included.
        for (op in FilterOperator.entries) {
            assertFalse("$op absent", m(op, false, emptyMap()))
            assertFalse("$op null", m(op, null, mapOf("k" to null)))
        }
    }
}
