package com.herald.sdk.push

import com.herald.sdk.ExternalPushCategory

/** `$external_push`: a push the app sent itself (not through Herald), so Herald's push limit can count it. */
internal object ExternalPush {
    const val EVENT = "\$external_push"

    /** Data key a sender can add: the id its backend reports. (`herald_message_id` is Herald's own push key.) */
    const val KEY_MESSAGE_ID = "herald_external_id"
    const val KEY_CATEGORY = "herald_category"
    const val MAX_MESSAGE_ID = 200
    const val MAX_SOURCE = 100
    const val TOO_LONG = "messageId must be at most $MAX_MESSAGE_ID characters and source at most $MAX_SOURCE"

    /** The event's properties; null when [messageId] or [source] is too long (the server would refuse the batch). */
    fun properties(messageId: String?, category: ExternalPushCategory, source: String?): Map<String, Any?>? {
        val props = mutableMapOf<String, Any?>("category" to category.wire)
        if (!messageId.isNullOrEmpty()) {
            if (messageId.length > MAX_MESSAGE_ID) return null
            props["message_id"] = messageId
        }
        if (!source.isNullOrEmpty()) {
            if (source.length > MAX_SOURCE) return null
            props["source"] = source
        }
        return props
    }

    /**
     * What auto-capture records for an FCM message: null for a Herald push, a Herald sync push, and a data-only
     * message ([hasNotification] false: not displayed). The id is `herald_external_id`, else [fcmMessageId].
     */
    fun capture(data: Map<String, String>, hasNotification: Boolean, fcmMessageId: String?): Pair<String?, ExternalPushCategory>? {
        if (!hasNotification || HeraldPushPayload.isSync(data) || HeraldPushPayload.fromData(data) != null) return null
        val own = data[KEY_MESSAGE_ID]?.takeIf { it.length in 1..MAX_MESSAGE_ID }
        val category = ExternalPushCategory.values().firstOrNull { it.wire == data[KEY_CATEGORY] } ?: ExternalPushCategory.OTHER
        return (own ?: fcmMessageId) to category
    }
}
