package com.herald.fcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeraldNotificationsTest {
    @Test
    fun parsesUpToThreeValidActions() {
        val json = """[
            {"id":"b1","title":"Start","deeplink":"lumen://start"},
            {"id":"","title":"No id"},
            {"id":"b2","title":"Later","deeplink":null},
            "junk",
            {"id":"b3","title":"Plans"},
            {"id":"b4","title":"Too many"}
        ]"""
        val actions = HeraldNotifications.parseActions(json)
        assertEquals(listOf("b1", "b2", "b3"), actions.map { it.id })
        assertEquals("lumen://start", actions[0].deeplink)
        assertNull(actions[1].deeplink)
    }

    @Test
    fun badActionsJsonIsIgnored() {
        assertTrue(HeraldNotifications.parseActions("{not json").isEmpty())
        assertTrue(HeraldNotifications.parseActions(null).isEmpty())
    }

    @Test
    fun readsTheExistingHeraldFcmPayload() {
        // Shape sent today by src/lib/esp/providers/fcm.ts: data carries the decision id and deeplink.
        val payload = HeraldNotifications.payloadOf(
            mapOf("nexus_decision_id" to "dec_9", "deeplink" to "lumen://plus", "title" to "Hi", "body" to "Body"),
            notification = null,
        )!!
        assertEquals("dec_9", payload.decisionId)
        assertEquals("lumen://plus", payload.deeplink)
        assertEquals("Hi", payload.title)
        assertNull(HeraldNotifications.payloadOf(mapOf("deeplink" to "x"), null))
    }

    @Test
    fun notificationImageSampleFitsTallAndWideImages() {
        assertEquals(1, HeraldNotifications.sampleSize(1024, 512))
        assertEquals(2, HeraldNotifications.sampleSize(2048, 1024))
        val tall = HeraldNotifications.sampleSize(1080, 25000)
        assertTrue(25000 / tall <= 1024 && 1080 / tall <= 1024)
    }
}
