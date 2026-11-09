package com.herald.sdk

import com.herald.sdk.internal.TriggerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delegate decisions, held and undelivered messages, and interaction logging through the core. */
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
    fun laterHoldsForShowNextWithoutDuplicatesAndDiscardDrops() {
        core.delegate = { InAppMessageDelegate { InAppDisplayDecision.LATER } }
        core.track("workout_completed", mapOf("minutes" to 32))
        h.clock.advance(60_000)
        core.track("workout_completed", mapOf("minutes" to 40))
        assertTrue(shown.isEmpty())
        assertEquals(1, core.heldCount)
        assertTrue(core.showNext())
        assertEquals("msg_2", shown.single().id)

        core.onInAppDismiss(shown.single())
        h.clock.advance(60_000)
        core.delegate = { InAppMessageDelegate { InAppDisplayDecision.DISCARD } }
        core.trigger(TriggerEvent(TriggerType.PUSH_OPEN))
        assertEquals(0, core.heldCount)
        assertEquals(1, shown.size)
    }

    @Test
    fun undeliveredMessageIsNotSpentAndShowsOnceOnNextResume() {
        core.display = { false } // between activities: nothing can draw
        core.track("workout_completed", mapOf("minutes" to 32))
        assertNull(core.engine.onScreen)
        assertEquals(listOf("msg_2"), core.undeliveredIds)

        // Not marked shown and the interval did not start: another trigger can still show at once.
        core.display = presenterAvailable
        core.retryUndelivered() // activity resumed
        assertEquals("msg_2", shown.single().id)
        assertTrue(core.undeliveredIds.isEmpty())
        core.onInAppDismiss(shown.single())

        h.clock.advance(60_000)
        core.retryUndelivered()
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
        restarted.retryUndelivered()
        assertEquals("msg_2", drawn.single().id)
    }

    @Test
    fun undeliveredExpiredMessageIsDropped() {
        core.display = { false }
        core.track("workout_completed", mapOf("minutes" to 32))
        h.clock.advance(365L * 24 * 3_600_000)
        h.transport.messagesResponse = { com.herald.sdk.internal.HttpResponse(200, emptyMap(), fixture("messages-empty.json")) }
        core.syncMessages()
        assertTrue(core.undeliveredIds.isEmpty())
    }

    @Test
    fun presenterGoneFreesTheScreen() {
        core.track("workout_completed", mapOf("minutes" to 32))
        core.presenterGone(shown.single())
        assertNull(core.engine.onScreen)
        assertFalse(interactions().any { it.getString("action") == "dismiss" && it.getString("messageId") == "msg_2" })
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
        assertTrue(core.undeliveredIds.isEmpty())

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
}
