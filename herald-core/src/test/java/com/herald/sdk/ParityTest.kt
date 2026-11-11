package com.herald.sdk

import com.herald.sdk.internal.PendingCalls
import com.herald.sdk.internal.PushPermissionRequest
import com.herald.sdk.internal.QueuedEvent
import com.herald.sdk.internal.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Behaviors that match the iOS SDK (`HeraldCore.swift`, `Herald.swift`). */
class ParityTest {
    private val h = CoreHarness().apply { transport.batchResponse = { throw IOException("offline") } }
    private val core = h.newCore()

    /** Every queued event, across users, oldest first. */
    private fun queued(): List<QueuedEvent> {
        val all = mutableListOf<QueuedEvent>()
        while (true) {
            val batch = core.queue.peekBatch(1000).takeIf { it.isNotEmpty() } ?: return all
            all += batch
            core.queue.remove(batch, 0)
        }
    }

    private fun List<QueuedEvent>.types() = map { it.event.getString("type") }

    // 1. Calls before configure

    @Test
    fun callsBeforeConfigureAreReplayedInOrderAndBounded() {
        val pending = PendingCalls<Int>(capacity = 3)
        (1..5).forEach { assertTrue(pending.offer(it)) }
        val replayed = mutableListOf<Int>()
        pending.start { replayed += it }
        assertEquals(listOf(1, 2, 3), replayed) // overflow dropped
        assertFalse(pending.offer(6)) // started: callers run directly
        pending.start { replayed += it } // a second configure replays nothing
        assertEquals(listOf(1, 2, 3), replayed)
        assertEquals(1000, PendingCalls.CAPACITY)
    }

    // 2. Session timeout from last activity

    @Test
    fun foregroundInactivityStartsANewSession() {
        core.onForeground()
        core.track("a", emptyMap())
        h.clock.advance(31 * 60_000) // still in the foreground, no calls
        core.track("b", emptyMap())
        val events = queued()
        assertEquals(listOf("session_start", "track", "session_end", "session_start", "track"), events.types())
        val end = events[2].event
        assertEquals(events[0].event.getString("sessionId"), end.getString("sessionId"))
        assertEquals(events[1].event.getString("timestamp"), end.getString("timestamp")) // stamped at last activity
        assertEquals(0L, end.getLong("durationSeconds"))
        assertNotEquals(end.getString("sessionId"), events[3].event.getString("sessionId"))
    }

    @Test
    fun activityKeepsTheSessionAlive() {
        core.onForeground()
        repeat(6) {
            h.clock.advance(20 * 60_000)
            core.track("tick", emptyMap())
        }
        assertEquals(1, queued().count { it.event.getString("type") == "session_start" })
    }

    @Test
    fun backgroundIsAnActivityBoundary() {
        core.onForeground()
        h.clock.advance(25 * 60_000)
        core.onBackground() // counts as activity
        h.clock.advance(25 * 60_000)
        core.onForeground()
        assertEquals(listOf("session_start"), queued().types())
    }

    // 3. reset() and user switch

    @Test
    fun resetEndsTheSessionStartsANewOneAndResendsThePushToken() {
        core.setPushToken("fcm_tok")
        core.identify("u_1", emptyMap())
        queued()
        h.clock.advance(1_000) // session ids are the start time in ms
        core.reset()
        val events = queued()
        assertEquals(listOf("session_end", "session_start", "push_token"), events.types())
        assertEquals("u_1", events[0].userId)
        assertTrue(events.drop(1).all { it.userId == null })
        assertNotEquals(events[0].event.getString("sessionId"), events[1].event.getString("sessionId"))
    }

    @Test
    fun userSwitchEndsTheSessionAndStartsANewOneForTheNewUser() {
        core.setPushToken("fcm_tok")
        core.identify("u_1", emptyMap())
        queued()
        h.clock.advance(1_000)
        core.identify("u_2", emptyMap())
        val events = queued()
        assertEquals(listOf("session_end", "session_start", "identify", "push_token"), events.types())
        assertEquals(listOf("u_1", "u_2", "u_2", "u_2"), events.map { it.userId })
    }

    @Test
    fun wipeDataQueuesNoSessionEnd() {
        core.track("a", emptyMap())
        core.wipeData()
        assertEquals(0, core.queue.size)
    }

    // 4. Push authorization records the subscription

    @Test
    fun grantedPushWaitsForTheToken() {
        core.recordPushAuthorization(granted = true)
        assertFalse(queued().any { it.event.getString("type") == "subscription" })
        core.setPushToken("fcm_tok")
        val events = queued().map { it.event }
        assertEquals(listOf("push_token", "subscription"), events.map { it.getString("type") }.takeLast(2))
        assertEquals("opted_in", events.last().getString("state"))
        // Sent once: a later token does not repeat it.
        core.setPushToken("fcm_tok_2")
        assertEquals(listOf("push_token"), queued().types())
    }

    @Test
    fun deniedPushIsSentAtOnceAndCancelsADeferredOptIn() {
        core.recordPushAuthorization(granted = true)
        core.recordPushAuthorization(granted = false)
        val events = queued().map { it.event }
        assertEquals("subscription", events.last().getString("type"))
        assertEquals("push", events.last().getString("channel"))
        assertEquals("unsubscribed", events.last().getString("state"))
        core.setPushToken("fcm_tok")
        assertEquals(listOf("push_token"), queued().types())
    }

    @Test
    fun deferredOptInSurvivesRestart() {
        core.recordPushAuthorization(granted = true)
        val restarted = h.newCore()
        restarted.setPushToken("fcm_tok")
        val events = restarted.queue.peekBatch(1000).map { it.event }
        assertEquals("opted_in", events.last().getString("state"))
    }

    @Test
    fun pushPermissionIsReadOnlyAfterThePromptPausedTheActivity() {
        val request = PushPermissionRequest()
        assertFalse(request.onResumed()) // nothing requested
        request.requested() // from onCreate/onStart: the activity's own first resume comes while the prompt is up
        assertFalse(request.onResumed())
        request.onPaused() // prompt shown
        assertTrue(request.onResumed()) // prompt closed: read the answer
        assertFalse(request.onResumed()) // once
        request.onPaused()
        assertFalse(request.onResumed())
    }

    @Test
    fun pushPermissionWithNoPromptIsReadOnTheNextPauseAndResume() {
        // Permanently denied: requestPermissions shows nothing; the next pause/resume cycle reads the answer.
        val request = PushPermissionRequest()
        request.requested()
        assertFalse(request.onResumed())
        request.onPaused()
        assertTrue(request.onResumed())
    }

    // 5. $email / $phone checked like the server

    @Test
    fun emailAndPhoneChecksMatchTheServer() {
        listOf("a@b.co", " a.b+c@x-y.example.org ", "o'brien@example.com").forEach { assertTrue(it, Validation.isValidEmail(it)) }
        listOf("", "a", "a@b", "a@b.c", ".a@b.co", "a..b@b.co", "a.@b.co", "a@-b.co", "a b@b.co").forEach { assertFalse(it, Validation.isValidEmail(it)) }
        listOf("+15551234567", "+4420794600").forEach { assertTrue(it, Validation.isValidPhone(it)) }
        listOf("15551234567", "+05551234567", "+1555", "+1 555 123 4567", "+1234567890123456").forEach { assertFalse(it, Validation.isValidPhone(it)) }
    }

    @Test
    fun setAttributesDropsOnlyAnInvalidEmailOrPhone() {
        core.setAttributes(mapOf("\$email" to "nope", "\$phone" to "555", "plan" to "pro"))
        val attrs = queued().last().event.getJSONObject("attributes")
        assertEquals(setOf("plan"), attrs.keys().asSequence().toSet())
    }

    @Test
    fun invalidSetEmailOrSetPhoneIsRejected() {
        // Herald.setEmail / setPhone route here.
        core.setAttributes(mapOf("\$email" to "nope"))
        core.setAttributes(mapOf("\$phone" to "555"))
        assertTrue(queued().isEmpty())
        core.setAttributes(mapOf("\$email" to "a@b.co"))
        core.setAttributes(mapOf("\$phone" to null)) // clearing is allowed
        assertEquals(listOf("session_start", "attributes", "attributes"), queued().types())
    }

    @Test
    fun identifyWithAnInvalidEmailTraitIsRejected() {
        core.identify("u_1", mapOf("\$email" to "nope"))
        assertTrue(queued().isEmpty())
    }

    // Review round

    @Test
    fun userSwitchSyncsOnceAndAnonymousToIdentifiedSyncsOnce() {
        core.onForeground() // anonymous session: one sync
        val before = h.transport.gets.size
        core.identify("u_1", emptyMap()) // same session: one sync for the user
        assertEquals(before + 1, h.transport.gets.size)
        core.identify("u_2", emptyMap()) // new session: its own sync only
        assertEquals(before + 2, h.transport.gets.size)
        assertTrue(h.transport.gets.last().url.contains("userId=u_2"))
    }

    @Test
    fun reEnablingInTheForegroundStartsTheSessionAtOnce() {
        core.onForeground()
        core.wipeData()
        core.setEnabled(true)
        assertEquals(listOf("session_start"), queued().types())
    }

    // Other gaps from the docs pass

    @Test
    fun currencyIsUpperCased() {
        core.trackPurchase("p", 1.0, "usd", 1, emptyMap())
        assertEquals("USD", queued().last().event.getString("currency"))
    }
}
