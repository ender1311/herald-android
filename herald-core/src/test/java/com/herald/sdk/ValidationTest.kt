package com.herald.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/** Calls the server would reject are refused locally, so they never cost a batch. */
class ValidationTest {
    private val h = CoreHarness()
    private val core = h.newCore()

    private fun queued() = core.queue.peekBatch(1000).map { it.event.getString("type") }

    @Test
    fun invalidCallsAreNeverQueued() {
        val long = "x".repeat(256)
        core.track("", emptyMap())
        core.track(long, emptyMap())
        core.track("ok", mapOf(long to 1))
        core.track("ok", mapOf("bad" to Any()))
        core.screen("", emptyMap())
        core.screen(long, emptyMap())
        core.identify("u".repeat(201), emptyMap())
        core.identify("u_1", mapOf("nested" to mapOf("a" to 1)))
        core.identify("u_1", mapOf("ints" to listOf(1, 2)))
        core.trackPurchase("", 1.0, "USD", 1, emptyMap())
        core.trackPurchase(long, 1.0, "USD", 1, emptyMap())
        core.trackPurchase("p", 1.0, "US", 1, emptyMap())
        core.trackPurchase("p", 1.0, "USD", 0, emptyMap())
        core.trackPurchase("p", Double.NaN, "USD", 1, emptyMap())
        core.setAttributes(mapOf("ok" to 1, "bad" to Any()))
        core.setAttributes(mapOf(long to 1))
        core.setAttributes(mapOf("list" to listOf(1, 2)))
        core.setPushToken("t".repeat(4097))
        assertEquals(emptyList<String>(), queued())
        assertEquals(null, core.userId)
    }

    @Test
    fun validCallsAtTheLimitsAreQueued() {
        val max = "x".repeat(255)
        core.identify("u".repeat(200), mapOf("plan" to "free", "tags" to listOf("a")))
        core.track(max, mapOf(max to mapOf("nested" to listOf(1, 2))))
        core.screen(max, emptyMap())
        core.trackPurchase(max, 0.0, "EUR", 1, emptyMap())
        core.setAttributes(mapOf(max to null))
        assertEquals(listOf("session_start", "identify", "track", "screen", "purchase", "attributes"), queued())
    }

    @Test
    fun emptyButtonIdIsRejected() {
        core.identify("u_1", emptyMap())
        val message = core.engine.messages.first { it.id == "msg_2" }
        core.onInAppClick(message, "")
        assertEquals(listOf("session_start", "identify"), queued())
    }

    @Test
    fun locationUsesReservedNumericAttributes() {
        core.setLocation(41.88, -87.63)
        core.setLocation(91.0, 0.0)
        val attrs = core.queue.peekBatch(100).last().event.getJSONObject("attributes")
        assertEquals(41.88, attrs.getDouble("\$latitude"), 0.0)
        assertEquals(-87.63, attrs.getDouble("\$longitude"), 0.0)
        assertEquals(2, queued().size)
    }
}
