package com.herald.sdk

import com.herald.sdk.internal.Backoff
import com.herald.sdk.internal.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test
    fun exponentialWithJitterAndCap() {
        val random = Random(1)
        for (failures in 1..12) {
            val ceiling = minOf(Backoff.MAX_MS, Backoff.BASE_MS shl (failures - 1))
            repeat(50) {
                val d = Backoff.delayMs(failures, null, random)
                assertTrue("failures=$failures d=$d", d in ceiling / 2..ceiling)
            }
        }
        assertTrue(Backoff.delayMs(1000, null, random) <= Backoff.MAX_MS)
    }

    @Test
    fun retryAfterWins() {
        assertEquals(30_000L, Backoff.delayMs(1, 30, Random(1)))
    }

    @Test
    fun readsRetryAfterHeaderThenBody() {
        assertEquals(12L, Backoff.retryAfterSeconds(HttpResponse(429, mapOf("retry-after" to "12"), ""), FIXTURE_NOW))
        assertEquals(30L, Backoff.retryAfterSeconds(HttpResponse(429, emptyMap(), fixture("errors.json").let { org.json.JSONObject(it).getJSONObject("429").toString() }), FIXTURE_NOW))
        assertNull(Backoff.retryAfterSeconds(HttpResponse(503, emptyMap(), "oops"), FIXTURE_NOW))
    }

    @Test
    fun readsRetryAfterHttpDateAndCapsAtAnHour() {
        // FIXTURE_NOW is 2026-11-09T14:00:00Z.
        assertEquals(90L, Backoff.retryAfterSeconds(HttpResponse(503, mapOf("Retry-After" to "Mon, 09 Nov 2026 14:01:30 GMT"), ""), FIXTURE_NOW))
        assertEquals(0L, Backoff.retryAfterSeconds(HttpResponse(503, mapOf("Retry-After" to "Mon, 09 Nov 2026 13:00:00 GMT"), ""), FIXTURE_NOW))
        assertEquals(3600L, Backoff.retryAfterSeconds(HttpResponse(503, mapOf("Retry-After" to "86400"), ""), FIXTURE_NOW))
    }
}
