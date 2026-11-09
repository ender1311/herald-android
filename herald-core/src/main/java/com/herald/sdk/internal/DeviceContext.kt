package com.herald.sdk.internal

import com.herald.sdk.DeviceDataCollection
import org.json.JSONObject

/** Snapshot of the device context sent with each batch. Tier-2 fields are dropped in minimal mode. */
internal data class DeviceContext(
    // Tier 1: always sent.
    val appName: String,
    val appVersion: String,
    val appBuild: String,
    val appNamespace: String,
    val osName: String,
    val osVersion: String,
    val region: String?,
    // Tier 2: omitted with DeviceDataCollection.MINIMAL.
    val manufacturer: String?,
    val model: String?,
    val deviceType: String?,
    val screenWidth: Int?,
    val screenHeight: Int?,
    val screenDensity: Number?,
    val locale: String?,
    val language: String?,
    val timezone: String?,
    val networkType: String?,
    val pushAuthorization: String?,
) {
    // Every string is cut to the server's context maxima (src/lib/sdk/schema.ts contextSchema), so one
    // over-long value (a CI versionName, say) cannot fail every batch.
    fun toJson(collection: DeviceDataCollection): JSONObject {
        val json = JSONObject()
            .put(
                "app",
                JSONObject().put("name", appName.clip(255)).put("version", appVersion.clip(64))
                    .put("build", appBuild.clip(64)).put("namespace", appNamespace.clip(255)),
            )
            .put("os", JSONObject().put("name", osName.ifEmpty { "unknown" }.clip(64)).put("version", osVersion.clip(64)))
        region?.let { json.put("region", it.clip(8)) }
        if (collection == DeviceDataCollection.MINIMAL) return json

        json.put(
            "device",
            JSONObject().putOpt("manufacturer", manufacturer?.clip(128)).putOpt("model", model?.clip(128))
                .putOpt("type", deviceType?.clip(32)),
        )
        if (screenWidth != null && screenHeight != null) {
            json.put(
                "screen",
                JSONObject().put("width", screenWidth).put("height", screenHeight).putOpt("density", screenDensity),
            )
        }
        json.putOpt("locale", locale?.clip(64)).putOpt("language", language?.clip(32)).putOpt("timezone", timezone?.clip(64))
        networkType?.let { json.put("network", JSONObject().put("type", it.clip(32))) }
        pushAuthorization?.let { json.put("push", JSONObject().put("authorization", it.clip(32))) }
        return json
    }

    /** Cuts to [max] UTF-16 units (the server's string length) without splitting a surrogate pair. */
    private fun String.clip(max: Int): String {
        if (length <= max) return this
        val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
        return substring(0, end)
    }
}
