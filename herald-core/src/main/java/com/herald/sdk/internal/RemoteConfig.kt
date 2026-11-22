package com.herald.sdk.internal

import com.herald.sdk.DeviceDataCollection
import com.herald.sdk.HeraldOptions
import org.json.JSONObject

/** The app's remote SDK settings (`GET /api/v1/sdk/config`), cached on disk and laid over the app's options. Same rule as iOS. */
internal class RemoteConfig(val version: String, private val settings: JSONObject) {
    /** The kill switch: `enabled: false` stops collection and sending. */
    val collects: Boolean get() = settings.bool("enabled") ?: true

    /** Remote values over the app's own; a missing or mistyped value keeps the app's. */
    fun applyTo(local: HeraldOptions): HeraldOptions = local.copy(
        flushIntervalSeconds = settings.num("flushIntervalSeconds")?.toLong() ?: local.flushIntervalSeconds,
        foregroundSessionTimeoutMinutes = minutes("foregroundSessionTimeoutSeconds") ?: local.foregroundSessionTimeoutMinutes,
        backgroundSessionTimeoutMinutes = minutes("backgroundSessionTimeoutSeconds") ?: local.backgroundSessionTimeoutMinutes,
        deviceDataCollection = when (settings.str("dataCollection")) {
            "standard" -> DeviceDataCollection.STANDARD
            "minimal" -> DeviceDataCollection.MINIMAL
            else -> local.deviceDataCollection
        },
        trackLifecycleEvents = settings.bool("trackLifecycleEvents") ?: local.trackLifecycleEvents,
        trackDeepLinks = settings.bool("trackDeepLinks") ?: local.trackDeepLinks,
    )

    // shortcut: Android timeouts are whole minutes, so seconds round to the nearest minute (the console only saves whole minutes).
    private fun minutes(key: String): Long? = settings.num(key)?.toLong()?.let { ((it + 30) / 60).coerceAtLeast(1) }

    companion object {
        /** A 200 body, `{ "data": { "version": …, "settings": { … } } }`; null when unreadable. */
        fun parse(body: String): RemoteConfig? = runCatching {
            val data = JSONObject(body).obj("data") ?: return null
            RemoteConfig(data.str("version") ?: return null, data.obj("settings") ?: JSONObject())
        }.getOrNull()
    }
}
