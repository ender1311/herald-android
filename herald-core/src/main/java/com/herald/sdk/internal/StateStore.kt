package com.herald.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Identity, session, message cache and in-app history, persisted as one JSON file. */
internal class StateStore(private val file: File) {
    var deviceId: String? = null
    var userId: String? = null
    var userToken: String? = null
    // The opt-out lives in its own marker file so a corrupt state file can never re-enable a disabled SDK.
    private val disabledMarker = File(file.parentFile, "${file.name}.disabled")
    var enabled: Boolean
        get() = !disabledMarker.exists()
        set(value) {
            if (value) {
                disabledMarker.delete()
            } else {
                disabledMarker.parentFile?.mkdirs()
                disabledMarker.createNewFile()
            }
        }
    var pushToken: String? = null
    var sessionId: String? = null
    var sessionStart: Long = 0
    var lastActivity: Long = 0
    var etag: String? = null
    var messagesBody: String? = null
    val dismissedCardIds: MutableSet<String> = mutableSetOf()
    val lastShown: MutableMap<String, Long> = mutableMapOf()
    /** Triggered in-app messages that no presenter could draw yet; retried on activity resume. */
    val undeliveredIds: MutableList<String> = mutableListOf()
    /** Recently counted push opens (decision or message ids), oldest first. */
    val recentPushOpens: MutableList<String> = mutableListOf()

    init {
        load()
    }

    fun save() {
        val json = JSONObject()
            .putOpt("deviceId", deviceId)
            .putOpt("userId", userId)
            .putOpt("userToken", userToken)
            .putOpt("pushToken", pushToken)
            .putOpt("sessionId", sessionId)
            .put("sessionStart", sessionStart)
            .put("lastActivity", lastActivity)
            .putOpt("etag", etag)
            .putOpt("messagesBody", messagesBody)
            .put("dismissedCardIds", JSONArray(dismissedCardIds.toList()))
            .put("lastShown", JSONObject(lastShown.toMap()))
            .put("undeliveredIds", JSONArray(undeliveredIds.toList()))
            .put("recentPushOpens", JSONArray(recentPushOpens.toList()))
        try {
            file.writeAtomically(json.toString())
        } catch (e: Exception) {
            HeraldLog.error("Could not persist SDK state", e)
        }
    }

    fun delete() {
        file.delete()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            deviceId = json.str("deviceId")
            userId = json.str("userId")
            userToken = json.str("userToken")
            pushToken = json.str("pushToken")
            sessionId = json.str("sessionId")
            sessionStart = json.num("sessionStart")?.toLong() ?: 0
            lastActivity = json.num("lastActivity")?.toLong() ?: 0
            etag = json.str("etag")
            messagesBody = json.str("messagesBody")
            json.arr("dismissedCardIds")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(dismissedCardIds::add) } }
            json.arr("undeliveredIds")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(undeliveredIds::add) } }
            json.arr("recentPushOpens")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(recentPushOpens::add) } }
            json.obj("lastShown")?.let { o -> o.keys().forEach { k -> o.num(k)?.let { lastShown[k] = it.toLong() } } }
        } catch (e: Exception) {
            HeraldLog.error("SDK state file unreadable; starting fresh", e)
        }
    }
}
