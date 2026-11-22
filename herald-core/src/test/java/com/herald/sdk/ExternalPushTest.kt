package com.herald.sdk

import com.herald.sdk.push.ExternalPush
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `$external_push`: payload → event and the public method; matches `ExternalPushTests.swift`. */
class ExternalPushTest {
    @Test
    fun heraldSyncAndDataOnlyMessagesAreNotCaptured() {
        assertNull(ExternalPush.capture(mapOf("nexus_decision_id" to "d1"), true, "f1"))
        assertNull(ExternalPush.capture(mapOf("herald_message_id" to "m1"), true, "f1"))
        assertNull(ExternalPush.capture(mapOf("herald_sync" to "1"), true, "f1"))
        assertNull("data-only, not displayed", ExternalPush.capture(mapOf("orderId" to "42"), false, "f1"))
    }

    @Test
    fun idsAndCategory() {
        assertEquals(
            "order-42" to ExternalPushCategory.TRANSACTIONAL,
            ExternalPush.capture(mapOf("herald_external_id" to "order-42", "herald_category" to "transactional"), true, "f1"),
        )
        assertEquals("f1" to ExternalPushCategory.OTHER, ExternalPush.capture(mapOf("herald_category" to "promo"), true, "f1"))
        assertEquals("f1" to ExternalPushCategory.OTHER, ExternalPush.capture(mapOf("herald_external_id" to "x".repeat(201)), true, "f1"))
    }

    private fun externalPushes(core: com.herald.sdk.internal.HeraldCore): List<JSONObject> =
        core.queue.peekBatch(100).map { it.event }.filter { it.getString("type") == "track" && it.getString("name") == "\$external_push" }

    @Test
    fun publicMethodRecordsTheEvent() {
        val h = CoreHarness()
        val core = h.newCore()
        core.trackExternalPush("order-42", ExternalPushCategory.TRANSACTIONAL, "orders", automatic = false)
        core.trackExternalPush(null, ExternalPushCategory.OTHER, null, automatic = false)
        core.trackExternalPush("x".repeat(201), ExternalPushCategory.OTHER, null, automatic = false)
        val events = externalPushes(core)
        assertEquals("a too-long id is rejected", 2, events.size)
        val first = events[0].getJSONObject("properties")
        assertEquals("transactional", first.getString("category"))
        assertEquals("order-42", first.getString("message_id"))
        assertEquals("orders", first.getString("source"))
        assertEquals("""{"category":"other"}""", events[1].getJSONObject("properties").toString())
    }

    @Test
    fun automaticCaptureFollowsTheFlag() {
        val h = CoreHarness(options = HeraldOptions(trackExternalPushes = false))
        val core = h.newCore()
        core.trackExternalPush("r1", ExternalPushCategory.OTHER, null, automatic = true)
        core.trackExternalPush("r2", ExternalPushCategory.OTHER, null, automatic = false)
        assertEquals(listOf("r2"), externalPushes(core).map { it.getJSONObject("properties").getString("message_id") })
    }
}
