package com.herald.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defects from the 0.2.2 hands-on QA, fixed in 0.2.3 (same cases as iOS `Fix023Tests`). */
class Fix023Test {
    private val h = CoreHarness()
    private val core = h.newCore()

    private fun events(): List<JSONObject> = h.transport.batches.flatMap { b ->
        val a = b.getJSONArray("events")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    // D1: after wipeData() + setEnabled(true) with notifications still allowed, the SDK asks for the FCM token
    // again and sends push_token then subscribed once it arrives.
    @Test
    fun reEnableAfterWipeRequestsThePushTokenAgain() {
        var requests = 0
        core.requestPushToken = { requests++ }
        core.setPushToken("fcm-token")
        core.flush()
        core.wipeData()
        core.setEnabled(true)
        assertEquals(1, requests)
        core.setPushToken("fcm-token") // what herald-fcm's fetch does
        core.flush()
        val last = h.transport.batches.last().getJSONArray("events")
        val tail = (0 until last.length()).map { last.getJSONObject(it) }.takeLast(2)
        assertEquals(listOf("push_token", "subscription"), tail.map { it.getString("type") })
        assertEquals("subscribed", tail.last().getString("state"))
    }

    @Test
    fun reEnableAfterWipeWithoutPermissionRequestsNothing() {
        val denied = h.newCore(fixtureContext.copy(pushAuthorization = "denied"))
        var requests = 0
        denied.requestPushToken = { requests++ }
        denied.wipeData()
        denied.setEnabled(true)
        assertEquals(0, requests)
        assertTrue(events().none { it.optString("state") == "subscribed" })
    }

    private fun impressions() = events().filter { it.getString("type") == "interaction" && it.getString("action") == "impression" }
        .map { it.getString("messageId") }

    // D3: one impression per card per session, across feed rebuilds and relaunches; a new session logs again.
    @Test
    fun cardImpressionLoggedOncePerSession() {
        core.debugLoadMessages(fixture("messages.json"))
        val id = h.cards.value.first().id
        core.logCardImpression(id)
        core.logCardImpression(id) // a second feed, or a filter change
        val relaunched = h.newCore()
        relaunched.debugLoadMessages(fixture("messages.json"))
        relaunched.logCardImpression(id)
        relaunched.flush()
        assertEquals(listOf(id), impressions())

        h.clock.advance(31 * 60_000) // the session times out
        relaunched.logCardImpression(id)
        relaunched.flush()
        assertEquals(listOf(id, id), impressions())
    }

    // D4: an expired card leaves the published list at its expiry time, without a sync.
    @Test
    fun expiredCardIsUnpublishedAtExpiry() {
        var scheduled: Long? = null
        core.scheduleCardRefresh = { scheduled = it }
        val soon = java.time.Instant.ofEpochMilli(FIXTURE_NOW + 70_000).toString()
        val body = """{"data":{"syncedAt":null,"inAppMessages":[],"contentCards":[
            {"id":"soon","type":"classic","createdAt":"2026-11-09T13:00:00Z","expiresAt":"$soon"},
            {"id":"stays","type":"classic","createdAt":"2026-11-09T13:00:00Z","expiresAt":null}]}}"""
        core.debugLoadMessages(body)
        assertEquals(listOf("soon", "stays"), h.cards.value.map { it.id })
        assertEquals(70_000L, scheduled)

        h.clock.advance(70_000)
        core.refreshCards() // what the facade runs at the scheduled time
        assertEquals(listOf("stays"), h.cards.value.map { it.id })
        assertEquals(null, scheduled) // nothing left to expire
        assertTrue("no sync", h.transport.gets.isEmpty())
    }

    // D8: once the server has refused an identified sync without a token, identified syncs wait for
    // setUserToken instead of collecting another 401.
    @Test
    fun identifiedSyncWaitsForTokenWhenVerificationIsRequired() {
        val normal = h.transport.messagesResponse
        h.transport.messagesResponse = { request ->
            if ("userId=" in request.url && "x-herald-user-token" !in request.headers) {
                com.herald.sdk.internal.HttpResponse(401, emptyMap(), """{"error":"Invalid user token"}""")
            } else {
                normal(request)
            }
        }
        core.identify("u1", emptyMap())
        val unauthorized = h.transport.gets.count { "userId=" in it.url && "x-herald-user-token" !in it.headers }
        assertEquals("one 401 teaches the SDK", 1, unauthorized)
        core.setUserToken("tok1")
        assertEquals("tok1", h.transport.gets.last().headers["x-herald-user-token"])

        val before = h.transport.gets.size
        core.identify("u2", emptyMap()) // a switch: new session, no token yet
        core.syncMessages()
        assertEquals("no request without the token", before, h.transport.gets.size)
        core.setUserToken("tok2")
        assertEquals(before + 1, h.transport.gets.size)
        assertEquals("tok2", h.transport.gets.last().headers["x-herald-user-token"])
    }

    @Test
    fun identifiedSyncWithoutVerificationStillRunsWithoutToken() {
        core.identify("u1", emptyMap())
        assertTrue(h.transport.gets.any { "userId=u1" in it.url })
        core.reset()
        core.identify("u2", emptyMap())
        assertTrue(h.transport.gets.any { "userId=u2" in it.url })
    }
}
