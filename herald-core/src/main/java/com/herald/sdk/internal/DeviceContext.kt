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
    fun toJson(collection: DeviceDataCollection): JSONObject {
        val json = JSONObject()
            .put(
                "app",
                JSONObject().put("name", appName).put("version", appVersion)
                    .put("build", appBuild).put("namespace", appNamespace),
            )
            .put("os", JSONObject().put("name", osName).put("version", osVersion))
        region?.let { json.put("region", it) }
        if (collection == DeviceDataCollection.MINIMAL) return json

        json.put(
            "device",
            JSONObject().putOpt("manufacturer", manufacturer).putOpt("model", model).putOpt("type", deviceType),
        )
        if (screenWidth != null && screenHeight != null) {
            json.put(
                "screen",
                JSONObject().put("width", screenWidth).put("height", screenHeight).putOpt("density", screenDensity),
            )
        }
        json.putOpt("locale", locale).putOpt("language", language).putOpt("timezone", timezone)
        networkType?.let { json.put("network", JSONObject().put("type", it)) }
        pushAuthorization?.let { json.put("push", JSONObject().put("authorization", it)) }
        return json
    }
}
