package com.herald.sdk

import com.herald.sdk.internal.TriggerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delegate decisions, pending messages, and interaction logging through the core. */
class InAppDisplayTest {
    private val h = CoreHarness()
    private val core = h.newCore()
    private val shown = mutableListOf<InAppMessage>()

    /** Simulates a presenter that is available and draws every message. */
    private val presenterAvailable: (InAppMessage) -> Boolean = { shown += it; core.presentAccepted(it); true }

    init {
        core.display = presenterAvailable
        core.identify("u_1", emptyMap())
        core.onForeground() // session_start shows msg_5
        core.onInAppDismiss(shown.single())
        shown.clear()
        h.clock.advance(60_000)
    }

    private fun interactions() = core.queue.peekBatch(1000).map { it.event }.filter { it.getString("type") == "interaction" }

    @Test
    fun displayLogsWithDecisionIdAndFreesScreenOnDismiss() {
        core.track("workout_completed", mapOf("minutes" to 32))
        val message = shown.single()
        assertEquals("msg_2", message.id)
        core.onInAppImpression(message)
        core.onInAppClick(message, "b1")
        core.onInAppDismiss(message)
        val actions = interactions().filter { it.getString("messageId") == "msg_2" }
        assertEquals(listOf("impression", "button"), actions.map { it.getString("action") })
        assertEquals("b1", actions[1].getString("buttonId"))
        assertTrue(actions.all { it.getString("decisionId") == "dec_2" && it.getString("kind") == "in_app" })
        assertNull(core.engine.onScreen)
    }

    @Test
    fun laterStaysPendingAndIsReOfferedAutomatically() {
        val asked = mutableListOf<String>()
        var decision = InAppDisplayDecision.LATER
        core.delegate = { InAppMessageDelegate { asked += it.id; decision } }
        core.track("workout_completed", mapOf("minutes" to 32))
        core.track("workout_completed", mapOf("minutes" to 40))
        assertTrue(shown.isEmpty())
        assertEquals(listOf("msg_2"), core.pendingIds) // no duplicate
        assertEquals(listOf("msg_2", "msg_2"), asked) // asked again at each display attempt

        decision = InAppDisplayDecision.DISPLAY
        core.showPendingIfPossible() // next activity resume
        assertEquals("msg_2", shown.single().id)
        assertTrue(core.pendingIds.isEmpty())
    }

    @Test
    fun discardDropsThePendingMessage() {
        core.delegate = { InAppMessageDelegate { InAppDisplayDecision.DISCARD } }
        core.trigger(TriggerEvent(TriggerType.PUSH_OPEN))
        assertTrue(core.pendingIds.isEmpty())
        core.delegate = { null }
        core.showPendingIfPossible()
        assertTrue(shown.isEmpty())
    }

    @Test
    fun triggerWhileAnotherIsOnScreenShowsAfterItClosesAndTheIntervalRunsOut() {
        val asked = mutableListOf<String>()
        core.delegate = { InAppMessageDelegate { asked += it.id; InAppDisplayDecision.DISPLAY } }
        val retries = mutableListOf<Long>()
        core.scheduleShowRetry = { retries += it }
        core.track("workout_completed", mapOf("minutes" to 32))
        val first = shown.single()
        core.onPushOpened("dec_9", "dec_9") // msg_6 matches while msg_2 is on screen
        assertEquals(listOf("msg_6"), core.pendingIds)
        assertEquals(listOf("msg_2"), asked) // the delegate is asked at display time, not at the trigger

        h.clock.advance(10_000)
        core.onInAppDismiss(first) // closed, but the 30 s minimum interval is still running
        assertEquals(1, shown.size)
        assertEquals(listOf(20_000L), retries)

        h.clock.advance(20_000)
        core.showPendingIfPossible() // the scheduled retry fires
        assertEquals(listOf("msg_2", "msg_6"), shown.map { it.id })
        assertEquals(listOf("msg_2", "msg_6"), asked)
    }

    @Test
    fun showNextShowsTheHighestPriorityPendingOldestFirstOnTiesAndSkipsTheInterval() {
        val base = core.engine.messages.first { it.id == "msg_6" }
        core.engine.messages = listOf(
            base.copy(id = "low", priority = 1),
            base.copy(id = "tie_new", priority = 7, trigger = base.trigger.copy(type = TriggerType.EVENT, event = "b")),
            base.copy(id = "tie_old", priority = 7, trigger = base.trigger.copy(type = TriggerType.EVENT, event = "a")),
        )
        core.delegate = { InAppMessageDelegate { InAppDisplayDecision.LATER } }
        core.trigger(TriggerEvent(TriggerType.PUSH_OPEN)) // low
        core.track("a", emptyMap()) // tie_old
        core.track("b", emptyMap()) // tie_new
        assertEquals(listOf("low", "tie_old", "tie_new"), core.pendingIds)

        core.delegate = { null }
        core.engine.beginPresenting(base) // another message on screen
        assertFalse(core.showNext())
        core.engine.recordShown(base) // starts the minimum interval
        core.engine.markDismissed()
        assertTrue(core.showNext()) // ignores the interval
        assertEquals("tie_old", shown.single().id)
    }

    @Test
    fun undeliveredMessageIsNotSpentAndShowsOnceOnNextResume() {
        core.display = { false } // between activities: nothing can draw
        core.track("workout_completed", mapOf("minutes" to 32))
        assertNull(core.engine.onScreen)
        assertEquals(listOf("msg_2"), core.pendingIds)

        // Not marked shown and the interval did not start: another trigger can still show at once.
        core.display = presenterAvailable
        core.showPendingIfPossible() // activity resumed
        assertEquals("msg_2", shown.single().id)
        assertTrue(core.pendingIds.isEmpty())
        core.onInAppDismiss(shown.single())

        h.clock.advance(60_000)
        core.showPendingIfPossible()
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals(1, shown.size) // once-only now spent
    }

    @Test
    fun undeliveredSurvivesRestartAndPresenterFailureLater() {
        core.display = { true } // handed to the main thread...
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals("msg_2", core.engine.onScreen?.id)
        core.track("other", emptyMap()) // screen reserved meanwhile
        core.presentFailed(core.engine.onScreen!!) // ...where the presenter declined
        assertNull(core.engine.onScreen)

        val restarted = h.newCore()
        val drawn = mutableListOf<InAppMessage>()
        restarted.display = { drawn += it; restarted.presentAccepted(it); true }
        restarted.onForeground() // shows pending messages
        assertEquals("msg_2", drawn.single().id)
    }

    @Test
    fun undeliveredExpiredMessageIsDropped() {
        core.display = { false }
        core.track("workout_completed", mapOf("minutes" to 32))
        h.clock.advance(365L * 24 * 3_600_000)
        h.transport.messagesResponse = { com.herald.sdk.internal.HttpResponse(200, emptyMap(), fixture("messages-empty.json")) }
        core.syncMessages()
        assertTrue(core.pendingIds.isEmpty())
    }

    @Test
    fun presenterGoneFreesTheScreenAndRetriesOnNextResume() {
        core.track("workout_completed", mapOf("minutes" to 32))
        core.presenterGone(shown.single()) // activity destroyed (rotation) before the user dismissed
        assertNull(core.engine.onScreen)
        assertFalse(interactions().any { it.getString("action") == "dismiss" && it.getString("messageId") == "msg_2" })
        assertEquals(listOf("msg_2"), core.pendingIds)

        // The display was undone, so the once-only message and the interval are not spent.
        core.showPendingIfPossible() // recreated activity resumed, no clock advance
        assertEquals(listOf("msg_2", "msg_2"), shown.map { it.id })
        core.onInAppDismiss(shown.last())
        h.clock.advance(60_000)
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals(2, shown.size) // now spent
    }

    @Test
    fun debugHooksLoadAndShowWithoutTriggers() {
        assertTrue(core.debugLoadMessages(fixture("messages.json")))
        assertEquals(3, core.engine.messages.size)
        assertFalse(core.debugLoadMessages("not json"))
        assertTrue(core.debugShowInAppMessage("msg_6"))
        assertEquals("msg_6", shown.single().id)
        assertFalse(core.debugShowInAppMessage("msg_2")) // one at a time
        core.onInAppDismiss(shown.single())
        assertFalse(core.debugShowInAppMessage("missing"))
    }

    @Test
    fun displayHistorySurvivesUserSwitchAndReset() {
        core.track("workout_completed", mapOf("minutes" to 32))
        core.onInAppDismiss(shown.single())
        var dismissedOnSwitch = 0
        core.dismissDisplay = { dismissedOnSwitch++ }

        core.identify("u_2", emptyMap())
        h.clock.advance(60_000)
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals(2, shown.size) // u_2 has its own history
        core.reset() // with msg_2 on screen for u_2
        assertEquals(1, dismissedOnSwitch)
        assertNull(core.engine.onScreen)

        core.identify("u_1", emptyMap())
        h.clock.advance(60_000)
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals(2, shown.size) // u_1 already saw the once-only message

        core.wipeData()
        core.setEnabled(true)
        core.identify("u_1", emptyMap())
        h.clock.advance(60_000)
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals(3, shown.size)
    }

    @Test
    fun lateConfirmationAfterUserSwitchIsIgnored() {
        core.display = { true } // handed to the main thread
        core.track("workout_completed", mapOf("minutes" to 32))
        val message = core.engine.onScreen!!
        core.identify("u_2", emptyMap()) // switch lands before the presenter answers
        core.presentAccepted(message)
        core.presentFailed(message)
        assertTrue(core.engine.lastShown.keys.none { it.endsWith("|msg_2") })
        assertFalse("msg_2" in core.pendingIds) // u_2's own session_start message may be pending

        core.identify("u_1", emptyMap())
        h.clock.advance(60_000)
        core.display = presenterAvailable
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals("msg_2", shown.single().id) // u_1 never actually saw it
    }

    @Test
    fun pushOpensWithoutDecisionIdAreNeverDeduped() {
        core.onPushOpened("msg_weekly", null)
        h.clock.advance(24 * 3_600_000L)
        core.onPushOpened("msg_weekly", null)
        val opens = interactions().filter { it.getString("kind") == "push" }
        assertEquals(2, opens.size)
        assertTrue(opens.all { it.getString("messageId") == "msg_weekly" && !it.has("decisionId") })
    }

    @Test
    fun pushOpenLogsOnceAndTriggers() {
        core.onPushOpened("dec_9", "dec_9")
        core.onPushOpened("dec_9", "dec_9") // redelivered intent
        assertEquals("msg_6", shown.single().id)
        val opens = interactions().filter { it.getString("kind") == "push" }
        assertEquals(1, opens.size)
        assertEquals("open", opens[0].getString("action"))
        assertEquals("dec_9", opens[0].getString("decisionId"))
    }

    @Test
    fun pushActionTapLogsOpenThenButtonOnce() {
        core.onPushOpened("dec_7", "dec_7", "b_start")
        core.onPushOpened("dec_7", "dec_7", "b_start") // redelivered intent
        val push = interactions().filter { it.getString("kind") == "push" }
        assertEquals(listOf("open", "button"), push.map { it.getString("action") })
        assertEquals("b_start", push[1].getString("buttonId"))
        assertTrue(push.all { it.getString("decisionId") == "dec_7" && it.getString("messageId") == "dec_7" })
        assertFalse(push[0].has("buttonId"))
    }
}
