package com.herald.sdk

import com.herald.sdk.internal.HeraldCore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchEncodingTest {
    private val full = JSONObject(fixture("batch-full.json"))
    private val minimal = JSONObject(fixture("batch-minimal.json"))

    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    @Test
    fun standardContextMatchesFixture() {
        val ctx = fixtureContext.toJson(DeviceDataCollection.STANDARD)
        assertTrue("context: $ctx", ctx.similar(full.getJSONObject("context")))
    }

    @Test
    fun minimalContextOmitsEveryTier2Field() {
        val ctx = fixtureContext.copy(osName = "Android", osVersion = "15").toJson(DeviceDataCollection.MINIMAL)
        assertTrue("context: $ctx", ctx.similar(minimal.getJSONObject("context")))
        for (key in listOf("locale", "language", "timezone", "device", "screen", "network", "push")) {
            assertFalse(key, ctx.has(key))
        }
    }

    @Test
    fun fullBatchMatchesFixtureStructure() {
        val ids = (listOf("6c1f2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b") +
            (1..20).map { "0b8f3c2e-0000-4000-8000-0000000000%02d".format(it) }).iterator()
        val h = CoreHarness(ids = ids)
        h.transport.batchResponse = { throw java.io.IOException("offline") } // keep everything queued
        val core = h.newCore()
        core.identify("u_123", mapOf("plan" to "free"))
        core.track("workout_completed", mapOf("minutes" to 32))
        core.screen("Home", emptyMap())
        core.trackPurchase("plus_annual", 59.99, "USD", 1, emptyMap())
        core.setAttributes(
            mapOf(
                "favorite_workout" to "strength", "workouts_30d" to 14, "beta_tester" to true,
                "goals" to listOf("strength", "mobility"), "\$email" to "maya@example.com",
            ),
        )
        core.setSubscription(SubscriptionChannel.PUSH, SubscriptionState.OPTED_IN)
        core.setPushToken("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")
        core.logCardClick("msg_1")
        core.onBackground()
        h.clock.advance(31 * 60_000)
        core.onForeground() // ends the old session

        val batch = JSONObject(core.encodeBatch(core.queue.peekBatch(100), 0))
        assertEquals(full.keySet(), batch.keySet())
        assertEquals("herald-android", batch.getJSONObject("sdk").getString("name"))
        assertEquals(full.getString("deviceId"), batch.getString("deviceId"))
        assertEquals("u_123", batch.getString("userId"))
        assertTrue(batch.getJSONObject("context").similar(full.getJSONObject("context")))

        val expected = full.getJSONArray("events")
        val actual = batch.getJSONArray("events")
        assertEquals(11, actual.length()) // fixture's 10 plus the new session_start
        for (i in 0 until expected.length()) {
            // sessionId is the session start in epoch ms; the fixture's value is arbitrary.
            val e = JSONObject(expected.getJSONObject(i).toString()).apply { remove("sessionId") }
            val a = JSONObject(actual.getJSONObject(i).toString()).apply { assertTrue(has("sessionId")); remove("sessionId") }
            when (e.getString("type")) {
                // Android sends FCM tokens: no APNs environment.
                "push_token" -> {
                    assertEquals(e.keySet() - "environment", a.keySet())
                    assertEquals("fcm", a.getString("platform"))
                    assertEquals(e.getString("token"), a.getString("token"))
                }
                // Timestamp and duration depend on the clock; shape must match.
                "session_end" -> assertEquals(e.keySet(), a.keySet())
                else -> assertTrue("event $i\nexpected $e\nactual   $a", e.similar(a))
            }
        }
        assertEquals("session_start", actual.getJSONObject(10).getString("type"))
    }

    @Test
    fun anonymousMinimalBatchMatchesFixtureStructure() {
        val h = CoreHarness(options = HeraldOptions(deviceDataCollection = DeviceDataCollection.MINIMAL))
        val core = h.newCore()
        core.track("app_opened", emptyMap())
        val batch = JSONObject(core.encodeBatch(core.queue.peekBatch(100), 3))
        assertEquals(minimal.keySet(), batch.keySet())
        assertEquals(JSONObject.NULL, batch.get("userId"))
        assertEquals(3, batch.getInt("dropped"))
        assertEquals(minimal.getJSONObject("context").keySet(), batch.getJSONObject("context").keySet())
        val events: JSONArray = batch.getJSONArray("events")
        for (i in 0 until events.length()) {
            assertEquals(
                minimal.getJSONArray("events").getJSONObject(i).keySet(),
                events.getJSONObject(i).keySet(),
            )
        }
        assertEquals(HeraldCore.SDK_NAME, minimal.getJSONObject("sdk").getString("name"))
    }
}
