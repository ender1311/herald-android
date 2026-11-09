package com.herald.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listener decisions, held messages and interaction logging through the core. */
class InAppDisplayTest {
    private val h = CoreHarness()
    private val core = h.newCore()
    private val shown = mutableListOf<InAppMessage>()

    init {
        core.display = { shown += it; true }
        core.identify("u_1", emptyMap())
        core.onForeground() // session_start fires msg_5
        shown.clear()
        core.onInAppDismiss(core.engine.onScreen!!)
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
        assertEquals(null, core.engine.onScreen)
    }

    @Test
    fun laterHoldsForShowNextAndDiscardDrops() {
        core.listener = { InAppMessageListener { InAppDisplayDecision.LATER } }
        core.track("workout_completed", mapOf("minutes" to 32))
        assertTrue(shown.isEmpty())
        assertEquals(1, core.pendingCount)
        assertTrue(core.showNext())
        assertEquals("msg_2", shown.single().id)

        core.onInAppDismiss(shown.single())
        h.clock.advance(60_000)
        core.listener = { InAppMessageListener { InAppDisplayDecision.DISCARD } }
        core.trigger(com.herald.sdk.internal.TriggerEvent(TriggerType.PUSH_OPEN))
        assertEquals(0, core.pendingCount)
        assertEquals(1, shown.size)
    }

    @Test
    fun noPresenterHoldsMessage() {
        core.display = { false }
        core.track("workout_completed", mapOf("minutes" to 32))
        assertEquals(1, core.pendingCount)
        assertEquals(null, core.engine.onScreen)
    }

    @Test
    fun pushOpenLogsAndTriggers() {
        core.onPushOpened("dec_9", "dec_9")
        assertEquals("msg_6", shown.single().id)
        val open = interactions().last { it.getString("kind") == "push" }
        assertEquals("open", open.getString("action"))
        assertEquals("dec_9", open.getString("decisionId"))
    }
}
