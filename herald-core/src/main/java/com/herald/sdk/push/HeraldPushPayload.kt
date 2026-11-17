package com.herald.sdk.push

import android.content.Context
import android.content.Intent
import com.herald.sdk.Herald

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
    /**
     * Copies the Herald fields into [intent] as string extras, plus this install's tap token, which
     * [com.herald.sdk.Herald.handleNotificationIntent] requires. The token comes from [context], or from the
     * context passed to `Herald.configure` when [context] is null; with neither, the tap will be ignored.
     */
    @JvmOverloads
    fun putInto(intent: Intent, context: Context? = null): Intent = intent.apply {
        decisionId?.let { putExtra(KEY_DECISION_ID, it) }
        messageId?.let { putExtra(KEY_MESSAGE_ID, it) }
        deeplink?.let { putExtra(KEY_DEEPLINK, it) }
        Herald.tapToken(context)?.let { putExtra(KEY_TAP_TOKEN, it) }
    }

    companion object {
        // Legacy key name: Herald's FCM provider already sends it, so it is external data and stays.
        const val KEY_DECISION_ID = "nexus_decision_id"
        const val KEY_MESSAGE_ID = "herald_message_id"
        const val KEY_DEEPLINK = "deeplink"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_IMAGE = "image"

        /** Data key of a Herald sync push (value "1"): a silent refresh, never shown or logged as an open. */
        const val KEY_SYNC = "herald_sync"

        /** True when an FCM data message is a Herald sync push. */
        fun isSync(data: Map<String, String>): Boolean = data[KEY_SYNC] == "1"

        /** Intent extra (Int) with the id of the notification herald-fcm posted, so a tap can clear it. */
        const val KEY_NOTIFICATION_ID = "herald_notification_id"

        /** Intent extra with the id of the notification action button the user tapped. */
        const val KEY_ACTION_ID = "herald_action_id"

        /** Intent extra with this install's tap token: proof the intent came from a Herald notification. */
        const val KEY_TAP_TOKEN = "herald_tap_token"

        /** Tag herald-fcm posts its notifications under. */
        const val NOTIFICATION_TAG = "herald"

        /** Parses an FCM `RemoteMessage.data` map; null when the message is not from Herald or is a sync push. */
        fun fromData(data: Map<String, String>): HeraldPushPayload? {
            if (isSync(data)) return null
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
