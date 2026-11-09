package com.herald.sdk.internal

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter; a server Retry-After wins when present. */
internal object Backoff {
    const val BASE_MS = 1_000L
    const val MAX_RETRY_AFTER_SECONDS = 3600L
    const val MAX_MS = 5 * 60_000L

    /** Delay before the next attempt after [failures] consecutive failures (failures >= 1). */
    fun delayMs(failures: Int, retryAfterSeconds: Long?, random: Random): Long {
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) return retryAfterSeconds * 1000
        val exp = min(MAX_MS, BASE_MS shl min(failures - 1, 20).coerceAtLeast(0))
        // Jitter in [exp/2, exp] spreads retries from many devices.
        return exp / 2 + random.nextLong(exp / 2 + 1)
    }

    /** Retry-After as delta seconds or an HTTP date, else `retryAfterSeconds` from the JSON body; capped at an hour. */
    fun retryAfterSeconds(response: HttpResponse, nowMs: Long): Long? {
        response.header("Retry-After")?.trim()?.let { header ->
            header.toDoubleOrNull()?.takeIf { it >= 0 }?.let { return min(ceil(it).toLong(), MAX_RETRY_AFTER_SECONDS) }
            runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()?.let { date ->
                val seconds = ceil((date.toInstant().toEpochMilli() - nowMs) / 1000.0).toLong()
                return seconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS)
            }
        }
        val body = runCatching { org.json.JSONObject(response.body).num("retryAfterSeconds")?.toDouble() }.getOrNull()
        return body?.takeIf { it >= 0 }?.let { min(ceil(it).toLong(), MAX_RETRY_AFTER_SECONDS) }
    }
}
