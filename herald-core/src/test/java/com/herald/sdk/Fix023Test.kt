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
}
