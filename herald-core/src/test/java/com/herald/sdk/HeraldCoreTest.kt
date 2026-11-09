package com.herald.sdk

import com.herald.sdk.internal.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.IOException

class HeraldCoreTest {
    private val h = CoreHarness()
    private val core = h.newCore()

    private fun status(code: Int, headers: Map<String, String> = emptyMap()) = { HttpResponse(code, headers, """{"error":"x"}""") }

    @Test
    fun successRemovesSentEvents() {
        core.track("a", emptyMap())
        core.flush()
        assertEquals(0, core.queue.size)
        val req = h.transport.requests.single { it.method == "POST" }
        assertEquals("https://herald.test/api/v1/sdk/batch", req.url)
        assertEquals("pk_test", req.headers["x-herald-app-key"])
        assertFalse(req.headers.containsKey("x-herald-user-token"))
    }

    @Test
    fun dropsBatchOn400_401_413() {
        for (code in listOf(400, 401, 413)) {
            h.transport.batchResponse = status(code)
            core.track("a", emptyMap())
            core.flush()
            assertEquals("status $code", 0, core.queue.size)
            assertEquals(0L, core.nextAttemptAt)
        }
    }

    @Test
    fun retries429WithRetryAfterAndKeepsEvents() {
        h.transport.batchResponse = status(429, mapOf("Retry-After" to "30"))
        core.track("a", emptyMap())
        core.flush()
        assertEquals(2, core.queue.size) // session_start + track
        assertEquals(FIXTURE_NOW + 30_000, core.nextAttemptAt)

        core.flush() // still backing off: no request
        assertEquals(1, h.transport.batches.size)

        h.clock.advance(30_000)
        h.transport.batchResponse = status(200)
        core.flush()
        assertEquals(0, core.queue.size)
        assertEquals(2, h.transport.batches.size)
        assertEquals(
            h.transport.batches[0].getJSONArray("events").getJSONObject(1).getString("id"),
            h.transport.batches[1].getJSONArray("events").getJSONObject(1).getString("id"),
        )
    }

    @Test
    fun serverErrorsAndNetworkFailuresBackOffExponentially() {
        h.transport.batchResponse = status(503)
        core.track("a", emptyMap())
        core.flush()
        val first = core.nextAttemptAt - h.clock.now
        assertTrue(first in 500..1_000)
        h.clock.advance(first)
        h.transport.batchResponse = { throw IOException("offline") }
        core.flush()
        val second = core.nextAttemptAt - h.clock.now
        assertTrue(second in 1_000..2_000)
        assertEquals(2, core.queue.size)
    }

    @Test
    fun queueSurvivesRestartWithoutDuplicates() {
        h.transport.batchResponse = { throw IOException("offline") }
        core.track("a", emptyMap())
        core.flush()
        val restarted = h.newCore()
        assertEquals(2, restarted.queue.size)
        assertEquals(core.deviceId, restarted.deviceId)
        h.transport.batchResponse = status(200)
        restarted.flush()
        assertEquals(0, restarted.queue.size)
        val ids = h.transport.batches.last().getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun droppedCountIsSentThenCleared() {
        h.transport.batchResponse = { throw IOException("offline") }
        repeat(1005) { core.screen("s$it", emptyMap()) }
        assertEquals(1000, core.queue.size)
        assertEquals(6, core.queue.dropped) // session_start + 5 oldest screens
        h.clock.advance(10 * 60_000)
        h.transport.batchResponse = status(200)
        core.flush()
        assertEquals(6, h.transport.batches.last { it.getInt("dropped") > 0 }.getInt("dropped"))
        assertEquals(0, core.queue.dropped)
        assertEquals(0, core.queue.size)
        assertTrue(h.transport.batches.all { it.getJSONArray("events").length() <= 100 })
    }

    @Test
    fun flushesAutomaticallyAtFlushAt() {
        repeat(19) { core.screen("s$it", emptyMap()) } // + session_start = 20
        assertEquals(1, h.transport.batches.size)
        assertEquals(20, h.transport.batches[0].getJSONArray("events").length())
    }

    @Test
    fun resetClearsUserStateAndNextBatchHasNoPreviousUser() {
        core.identify("u_1", emptyMap())
        core.setUserToken("tok_1")
        assertEquals(3, h.cards.value.size)
        core.track("before", emptyMap())
        core.reset()

        assertNull(core.userId)
        assertTrue(h.cards.value.isEmpty())
        assertTrue(core.engine.messages.isEmpty())

        core.track("after", emptyMap())
        core.flush()
        val (old, fresh) = h.transport.batches
        assertEquals("u_1", old.getString("userId"))
        assertEquals("tok_1", h.transport.requests.filter { it.method == "POST" }[0].headers["x-herald-user-token"])
        assertEquals(org.json.JSONObject.NULL, fresh.get("userId"))
        assertNull(h.transport.requests.filter { it.method == "POST" }[1].headers["x-herald-user-token"])
        val freshEvents = fresh.getJSONArray("events")
        assertEquals("after", freshEvents.getJSONObject(freshEvents.length() - 1).getString("name"))

        // A restart does not bring the old user back.
        val restarted = h.newCore()
        assertNull(restarted.userId)
        assertTrue(h.cards.value.isEmpty())
    }

    @Test
    fun identifyingAnotherUserDropsPreviousUsersMessages() {
        core.identify("u_1", emptyMap())
        core.logCardDismissal("msg_1")
        h.transport.messagesResponse = { HttpResponse(200, emptyMap(), fixture("messages-empty.json")) }
        core.identify("u_2", emptyMap())
        assertTrue(h.cards.value.isEmpty())
        assertNull(h.transport.gets.last().headers["If-None-Match"])
        assertTrue(h.transport.gets.last().url.contains("userId=u_2"))
    }

    @Test
    fun messageSyncUsesEtagAnd304KeepsCache() {
        core.identify("u_1", emptyMap())
        assertNull(h.transport.gets[0].headers["If-None-Match"])
        h.transport.messagesResponse = { HttpResponse(304, emptyMap(), "") }
        core.syncMessages()
        assertEquals("\"v1\"", h.transport.gets[1].headers["If-None-Match"])
        assertEquals(listOf("msg_1", "msg_3", "msg_4"), h.cards.value.map { it.id })

        // Cached messages load offline after a restart.
        h.cards.value = emptyList()
        val restarted = h.newCore()
        assertEquals(3, h.cards.value.size)
        assertEquals(3, restarted.engine.messages.size)
    }

    @Test
    fun setUserTokenAfterIdentifySyncsWithTheToken() {
        core.identify("u_1", emptyMap())
        assertNull(h.transport.gets.last().headers["x-herald-user-token"])
        core.setUserToken("tok_1")
        assertEquals("tok_1", h.transport.gets.last().headers["x-herald-user-token"])
        val gets = h.transport.gets.size
        core.setUserToken("tok_1") // unchanged: no extra request
        assertEquals(gets, h.transport.gets.size)
    }

    @Test
    fun anonymousUsersSyncWithoutUserId() {
        core.syncMessages()
        val get = h.transport.gets.single()
        assertEquals("https://herald.test/api/v1/sdk/messages?deviceId=${core.deviceId}", get.url)
        assertNull(get.headers["x-herald-user-token"])
    }

    @Test
    fun cardInteractionsCarryDecisionIdAndDismissalSticks() {
        core.identify("u_1", emptyMap())
        val card = h.cards.value.first()
        core.logCardImpression(card.id)
        core.logCardImpression(card.id) // once per session
        core.logCardClick(card.id)
        core.logCardDismissal(card.id)
        assertFalse(h.cards.value.any { it.id == card.id })

        core.syncMessages() // server still returns it: stays hidden
        assertFalse(h.cards.value.any { it.id == card.id })

        core.flush()
        val interactions = h.transport.batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
            .filter { it.getString("type") == "interaction" }
        assertEquals(listOf("impression", "click", "dismiss"), interactions.map { it.getString("action") })
        assertTrue(interactions.all { it.getString("decisionId") == "dec_1" && it.getString("kind") == "content_card" })
    }

    @Test
    fun sessionTimesOutAfterBackgroundGap() {
        core.onForeground()
        val firstSession = core.queue.peekBatch(100).first().event.getString("sessionId")
        core.onBackground()
        h.clock.advance(29 * 60_000)
        core.onForeground()
        core.track("same", emptyMap())
        core.onBackground()
        h.clock.advance(31 * 60_000)
        core.onForeground()
        val events = h.transport.batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } +
            core.queue.peekBatch(100).map { it.event }
        assertEquals(listOf("session_start", "track", "session_end", "session_start"), events.map { it.getString("type") })
        assertEquals(firstSession, events[1].getString("sessionId"))
        assertEquals(29 * 60L, events[2].getLong("durationSeconds"))
        assertNotEquals(firstSession, events[3].getString("sessionId"))
    }

    @Test
    fun disabledSdkCollectsAndSendsNothing() {
        core.setEnabled(false)
        core.track("a", emptyMap())
        core.flush()
        assertEquals(0, core.queue.size)
        assertTrue(h.transport.requests.isEmpty())
    }

    @Test
    fun wipeDataDeletesEverythingAndDisablesUntilReEnabled() {
        h.transport.batchResponse = { throw IOException("offline") }
        core.setPushToken("fcm_tok")
        core.identify("u_1", emptyMap())
        val oldDevice = core.deviceId
        core.wipeData()
        assertEquals(0, core.queue.size)
        assertNull(core.userId)
        assertFalse(core.enabled)
        // Nothing is collected or stored while disabled, even across a restart.
        core.onForeground()
        core.track("after_wipe", emptyMap())
        core.setPushToken("fcm_tok_2") // HeraldMessagingService syncs the token on every launch
        core.identify("u_2", emptyMap())
        core.setUserToken("tok_2")
        core.setAttributes(mapOf("plan" to "pro"))
        core.onPushOpened("msg_1", "dec_1")
        core.reset()
        core.onBackground()
        core.flush()
        assertEquals(0, core.queue.size)
        assertNull(core.userId)
        assertFalse(java.io.File(h.dir, "herald_state.json").exists())
        assertFalse(java.io.File(h.dir, "herald_queue.json").exists())
        val restarted = h.newCore()
        assertFalse(restarted.enabled)
        assertFalse(java.io.File(h.dir, "herald_state.json").exists())

        restarted.setEnabled(true)
        assertNotEquals(oldDevice, restarted.deviceId)
        val reloaded = h.newCore()
        assertEquals(restarted.deviceId, reloaded.deviceId)
        assertNull(reloaded.userId) // nothing from the disabled period carried over
        restarted.track("back", emptyMap())
        val events = restarted.queue.peekBatch(100)
        assertEquals(listOf("session_start", "track"), events.map { it.event.getString("type") })
        assertTrue(events.all { it.userId == null })
        assertFalse(events.any { it.event.getString("type") == "push_token" })
    }

    @Test
    fun identifyResendsStoredPushToken() {
        h.transport.batchResponse = { throw IOException("offline") }
        core.setPushToken("fcm_tok")
        core.identify("u_1", emptyMap()) // anonymous -> identified
        core.identify("u_1", emptyMap()) // same user: no re-send
        core.identify("u_2", emptyMap()) // user switch
        val tokens = generateSequence { core.queue.peekBatch(100).takeIf { it.isNotEmpty() } }
            .flatMap { batch -> core.queue.removeFirst(batch.size, 0); batch }
            .filter { it.event.getString("type") == "push_token" }
            .toList()
        assertEquals(listOf(null, "u_1", "u_2"), tokens.map { it.userId })
        assertTrue(tokens.all { it.event.getString("token") == "fcm_tok" })
    }

    @Test
    fun pushTokenPrecedesPushSubscriptionInABatch() {
        core.setSubscription(SubscriptionChannel.EMAIL, SubscriptionState.SUBSCRIBED)
        core.setSubscription(SubscriptionChannel.PUSH, SubscriptionState.OPTED_IN)
        core.track("between", emptyMap())
        core.setPushToken("fcm_tok")
        core.flush()
        val events = h.transport.batches.single().getJSONArray("events")
        val types = (0 until events.length()).map { events.getJSONObject(it).let { e -> e.getString("type") + ":" + e.optString("channel") } }
        // The push opt-in waits for the token (as on iOS); the email one does not.
        assertEquals(listOf("session_start:", "subscription:email", "track:", "push_token:", "subscription:push"), types)
    }

    @Test
    fun tokenSetAfterIdentifyCoversEarlierEventsOfThatUser() {
        core.identify("u_1", emptyMap())
        core.track("before_token", emptyMap())
        core.setUserToken("tok_1")
        core.identify("u_2", emptyMap()) // switch before any flush
        core.flush()
        val post = h.transport.requests.first { it.method == "POST" }
        assertEquals("u_1", JSONObject(post.body!!).getString("userId"))
        assertEquals("tok_1", post.headers["x-herald-user-token"])
    }

    @Test
    fun disabledFlagSurvivesCorruptStateFile() {
        core.setEnabled(false)
        java.io.File(h.dir, "herald_state.json").writeText("{corrupt")
        assertFalse(h.newCore().enabled)
    }
}
