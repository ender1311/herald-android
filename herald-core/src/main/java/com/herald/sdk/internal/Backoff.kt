package com.herald.sdk.internal

import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter; a server Retry-After wins when present. */
internal object Backoff {
    const val BASE_MS = 2_000L
    const val MAX_MS = 5 * 60_000L

    /** Delay before the next attempt after [failures] consecutive failures (failures >= 1). */
    fun delayMs(failures: Int, retryAfterSeconds: Long?, random: Random): Long {
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) return retryAfterSeconds * 1000
        val exp = min(MAX_MS, BASE_MS shl min(failures - 1, 20).coerceAtLeast(0))
        // Jitter in [exp/2, exp] spreads retries from many devices.
        return exp / 2 + random.nextLong(exp / 2 + 1)
    }

    /** Retry-After as delta seconds (HTTP-date form is ignored), else `retryAfterSeconds` from the JSON body. */
    fun retryAfterSeconds(response: HttpResponse): Long? {
        response.header("Retry-After")?.trim()?.toLongOrNull()?.let { return it }
        return runCatching { org.json.JSONObject(response.body).num("retryAfterSeconds")?.toLong() }.getOrNull()
    }
}
