package com.herald.sdk.internal

import android.content.Context
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
    /** A push opt-in made before any token existed; sent once the token arrives. Wire value. */
    var deferredPushSubscription: String? = null
    var sessionId: String? = null
    var sessionStart: Long = 0
    var lastActivity: Long = 0
    var etag: String? = null
    var messagesBody: String? = null
    val dismissedCardIds: MutableSet<String> = mutableSetOf()
    val lastShown: MutableMap<String, Long> = mutableMapOf()
    /** Triggered in-app messages not shown yet, oldest first (persisted key kept from the first release). */
    val undeliveredIds: MutableList<String> = mutableListOf()
    /** Recently counted push opens (decision or message ids), oldest first. */
    val recentPushOpens: MutableList<String> = mutableListOf()
    /** Cards whose impression this session already logged; cleared when a session starts. */
    val impressedCardIds: MutableSet<String> = mutableSetOf()
    /**
     * The server refused an identified request without a user token (identity verification is required):
     * identified syncs wait for `setUserToken` instead of collecting a 401.
     */
    var userTokenRequired = false
    /** The current user's User ID (XXXX-XXXX) from the last accepted batch; null while anonymous. */
    var heraldId: String? = null
    /** The app version and build last launched, for `$app_installed` / `$app_updated`. */
    var appVersion: String? = null
    var appBuild: String? = null
    /** Sessions started on this install (`session_index` of the latest) and the latest session's id. */
    var sessionIndex: Long = 0
    var lastSessionId: String? = null
    /** The app went to the background and hasn't come back: sessions time out after the background timeout. */
    var inBackground = false
    /** The app's `setConsent` record (`basis`, `documentId`, `documentVersion`, `recordedAt`), sent in each batch's context. */
    var consent: JSONObject? = null

    init {
        load()
    }

    fun save() {
        // No device id means wiped and disabled: nothing may be written until setEnabled(true) makes a new one.
        if (deviceId == null) return
        val json = JSONObject()
            .putOpt("deviceId", deviceId)
            .putOpt("userId", userId)
            .putOpt("userToken", userToken)
            .putOpt("pushToken", pushToken)
            .putOpt("deferredPushSubscription", deferredPushSubscription)
            .putOpt("sessionId", sessionId)
            .put("sessionStart", sessionStart)
            .put("lastActivity", lastActivity)
            .putOpt("etag", etag)
            .putOpt("messagesBody", messagesBody)
            .put("dismissedCardIds", JSONArray(dismissedCardIds.toList()))
            .put("lastShown", JSONObject(lastShown.toMap()))
            .put("undeliveredIds", JSONArray(undeliveredIds.toList()))
            .put("recentPushOpens", JSONArray(recentPushOpens.toList()))
            .put("impressedCardIds", JSONArray(impressedCardIds.toList()))
            .put("userTokenRequired", userTokenRequired)
            .putOpt("heraldId", heraldId)
            .putOpt("appVersion", appVersion)
            .putOpt("appBuild", appBuild)
            .put("sessionIndex", sessionIndex)
            .putOpt("lastSessionId", lastSessionId)
            .put("inBackground", inBackground)
            .putOpt("consent", consent)
        try {
            file.writeAtomically(json.toString())
        } catch (e: Exception) {
            HeraldLog.error("Could not persist SDK state", e)
        }
    }

    fun delete() {
        file.delete()
    }

    companion object {
        /** SDK files live in `noBackupFilesDir/herald`, which Android Auto Backup never copies. */
        const val STORAGE_DIR = "herald"
        const val FILE_NAME = "herald_state.json"

        /**
         * The SDK's storage directory. SDK 0.4.0 and earlier kept it in `filesDir/herald`, which Auto Backup copies
         * (device ID, user token, queued events) to a new phone; the first call moves it out of there.
         */
        fun storageDir(context: Context): File =
            moveLegacy(context.filesDir.resolve(STORAGE_DIR), context.noBackupFilesDir.resolve(STORAGE_DIR))

        /** Moves [legacy] to [target] when only [legacy] exists, drops a stale [legacy] otherwise; returns [target]. */
        internal fun moveLegacy(legacy: File, target: File): File = synchronized(this) {
            if (!legacy.exists()) return target
            if (target.exists()) {
                legacy.deleteRecursively()
                return target
            }
            target.parentFile?.mkdirs()
            val moved = legacy.renameTo(target) || runCatching { legacy.copyRecursively(target, overwrite = true) }.getOrDefault(false)
            if (moved) {
                legacy.deleteRecursively()
            } else {
                target.deleteRecursively() // a partial copy; the next launch tries again
                HeraldLog.warn("Could not move SDK storage out of the backed-up files directory")
                return legacy
            }
            target
        }

        /** Whether the SDK in [dir] is disabled, read straight from the marker file (no state load). */
        fun isDisabled(dir: File): Boolean = File(dir, "$FILE_NAME.disabled").exists()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            deviceId = json.str("deviceId")
            userId = json.str("userId")
            userToken = json.str("userToken")
            pushToken = json.str("pushToken")
            deferredPushSubscription = json.str("deferredPushSubscription")
            sessionId = json.str("sessionId")
            sessionStart = json.num("sessionStart")?.toLong() ?: 0
            lastActivity = json.num("lastActivity")?.toLong() ?: 0
            etag = json.str("etag")
            messagesBody = json.str("messagesBody")
            userTokenRequired = json.optBoolean("userTokenRequired", false)
            heraldId = json.str("heraldId")
            appVersion = json.str("appVersion")
            appBuild = json.str("appBuild")
            sessionIndex = json.num("sessionIndex")?.toLong() ?: 0
            lastSessionId = json.str("lastSessionId")
            inBackground = json.optBoolean("inBackground", false)
            consent = json.obj("consent")
            json.arr("dismissedCardIds")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(dismissedCardIds::add) } }
            json.arr("undeliveredIds")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(undeliveredIds::add) } }
            json.arr("recentPushOpens")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(recentPushOpens::add) } }
            json.arr("impressedCardIds")?.let { a -> (0 until a.length()).forEach { i -> (a.opt(i) as? String)?.let(impressedCardIds::add) } }
            json.obj("lastShown")?.let { o -> o.keys().forEach { k -> o.num(k)?.let { lastShown[k] = it.toLong() } } }
        } catch (e: Exception) {
            HeraldLog.error("SDK state file unreadable; starting fresh", e)
        }
    }
}
