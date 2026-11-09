package com.herald.sdk

import com.herald.sdk.push.HeraldPushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeraldPushPayloadTest {
    @Test
    fun parsesHeraldDataAndIgnoresOthers() {
        val p = HeraldPushPayload.fromData(mapOf("nexus_decision_id" to "dec_9", "deeplink" to "lumen://plus", "image" to "https://x/y.png"))!!
        assertEquals("dec_9", p.decisionId)
        assertEquals("lumen://plus", p.deeplink)
        assertEquals("https://x/y.png", p.imageUrl)
        assertNull(HeraldPushPayload.fromData(mapOf("deeplink" to "other://")))
    }
}
