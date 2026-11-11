package com.herald.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** I5: Herald.openUrl routes links through deepLinkHandler before ACTION_VIEW, as iOS does. */
class DeepLinkHandlerTest {
    @After
    fun clearHandler() {
        Herald.deepLinkHandler = null
    }

    @Test
    fun openUrlAsksTheHandlerFirst() {
        val seen = mutableListOf<String>()
        Herald.deepLinkHandler = { url -> seen += url; url.startsWith("lumen://") }
        Herald.openUrl("lumen://plan/today")
        Herald.openUrl("https://example.com") // handler returns false: falls back to ACTION_VIEW (no context in tests)
        assertEquals(listOf("lumen://plan/today", "https://example.com"), seen)
    }

    @Test
    fun aThrowingHandlerNeverReachesTheApp() {
        Herald.deepLinkHandler = { throw IllegalStateException("boom") }
        Herald.openUrl("lumen://x") // logged, not thrown
    }
}
