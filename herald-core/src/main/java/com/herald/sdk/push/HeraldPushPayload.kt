package com.herald.sdk.push

import android.content.Intent

/**
 * The Herald fields of an FCM data message, and the extras a notification tap intent carries.
 * Used by a FirebaseMessagingService (herald-core's HeraldMessagingService or the app's own)
 * and by [com.herald.sdk.Herald.handleNotificationIntent].
 */
data class HeraldPushPayload(
    val decisionId: String?,
    val messageId: String?,
    val deeplink: String?,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
) {
    /** Copies the Herald fields into [intent] as string extras. */
    fun putInto(intent: Intent): Intent = intent.apply {
        decisionId?.let { putExtra(KEY_DECISION_ID, it) }
        messageId?.let { putExtra(KEY_MESSAGE_ID, it) }
        deeplink?.let { putExtra(KEY_DEEPLINK, it) }
    }

    companion object {
        // Legacy key name: Herald's FCM provider already sends it, so it is external data and stays.
        const val KEY_DECISION_ID = "nexus_decision_id"
        const val KEY_MESSAGE_ID = "herald_message_id"
        const val KEY_DEEPLINK = "deeplink"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_IMAGE = "image"

        /** Parses an FCM `RemoteMessage.data` map; null when the message is not from Herald. */
        fun fromData(data: Map<String, String>): HeraldPushPayload? {
            if (data[KEY_DECISION_ID] == null && data[KEY_MESSAGE_ID] == null) return null
            return HeraldPushPayload(
                decisionId = data[KEY_DECISION_ID],
                messageId = data[KEY_MESSAGE_ID],
                deeplink = data[KEY_DEEPLINK],
                title = data[KEY_TITLE],
                body = data[KEY_BODY],
                imageUrl = data[KEY_IMAGE],
            )
        }

        /** Reads the Herald extras from a notification tap intent; null when it did not come from Herald. */
        fun fromIntent(intent: Intent?): HeraldPushPayload? {
            val extras = intent?.extras ?: return null
            val data = listOf(KEY_DECISION_ID, KEY_MESSAGE_ID, KEY_DEEPLINK)
                .mapNotNull { key -> extras.getString(key)?.let { key to it } }
                .toMap()
            return fromData(data)
        }
    }
}
