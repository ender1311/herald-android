package com.herald.sdk.internal

import com.herald.sdk.ButtonAction
import com.herald.sdk.ButtonActionType
import com.herald.sdk.ContentCard
import com.herald.sdk.ContentCardType
import com.herald.sdk.FilterOperator
import com.herald.sdk.InAppButton
import com.herald.sdk.InAppContent
import com.herald.sdk.InAppMessage
import com.herald.sdk.InAppMessageType
import com.herald.sdk.InAppStyle
import com.herald.sdk.InAppTrigger
import com.herald.sdk.PropertyFilter
import com.herald.sdk.ReEligibility
import com.herald.sdk.ReEligibilityMode
import com.herald.sdk.TriggerType
import org.json.JSONArray
import org.json.JSONObject

internal data class MessagesPayload(
    val contentCards: List<ContentCard>,
    val inAppMessages: List<InAppMessage>,
)

/** Decodes `GET /api/v1/sdk/messages`. Malformed or unknown items are skipped, never fatal. */
internal object MessageParser {

    /** Parses the full response body (`{ "data": { ... } }`); null when the envelope itself is unusable. */
    fun parse(body: String): MessagesPayload? {
        val data = runCatching { JSONObject(body).obj("data") }.getOrNull() ?: return null
        return MessagesPayload(
            contentCards = parseEach(data.arr("contentCards"), ::parseCard),
            inAppMessages = parseEach(data.arr("inAppMessages"), ::parseInApp),
        )
    }

    private fun <T> parseEach(array: JSONArray?, parse: (JSONObject) -> T?): List<T> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i)
            val parsed = item?.let { runCatching { parse(it) }.getOrNull() }
            if (parsed == null) HeraldLog.warn("Skipping malformed message at index $i: ${item?.opt("id")}")
            parsed
        }
    }

    private inline fun <reified E : Enum<E>> enumOf(wire: String?, value: (E) -> String): E? =
        enumValues<E>().firstOrNull { value(it) == wire }

    fun parseCard(o: JSONObject): ContentCard? {
        val id = o.str("id") ?: return null
        val type = enumOf<ContentCardType>(o.str("type")) { it.wire } ?: return null
        return ContentCard(
            id = id,
            type = type,
            title = o.str("title"),
            body = o.str("body"),
            imageUrl = o.str("imageUrl"),
            cta = o.str("cta"),
            url = o.str("url"),
            extras = o.obj("extras")?.toMap() ?: emptyMap(),
            pinned = o.bool("pinned") ?: false,
            dismissible = o.bool("dismissible") ?: true,
            categories = o.arr("categories")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } } ?: emptyList(),
            createdAt = parseIso(o.str("createdAt")) ?: return null,
            expiresAt = parseIso(o.str("expiresAt")),
            viewed = o.bool("viewed") ?: false,
            clicked = o.bool("clicked") ?: false,
            dismissed = o.bool("dismissed") ?: false,
            decisionId = o.str("decisionId"),
        )
    }

    fun parseInApp(o: JSONObject): InAppMessage? {
        val id = o.str("id") ?: return null
        val type = enumOf<InAppMessageType>(o.str("type")) { it.wire } ?: return null
        val trigger = o.obj("trigger")?.let(::parseTrigger) ?: return null
        val re = o.obj("reEligibility")
        val mode = enumOf<ReEligibilityMode>(re?.str("mode")) { it.wire } ?: return null
        val hours = re?.num("hours")?.toDouble()
        if (mode == ReEligibilityMode.AFTER_HOURS && hours == null) return null
        val c = o.obj("content") ?: return null
        val content = InAppContent(
            header = c.str("header"),
            body = c.str("body"),
            imageUrl = c.str("imageUrl"),
            buttons = c.arr("buttons")?.let { a -> (0 until a.length()).map { parseButton(a.getJSONObject(it)) ?: return null } } ?: emptyList(),
            html = c.str("html"),
            slideFrom = c.str("slideFrom"),
            dismissAfterSeconds = c.num("dismissAfterSeconds")?.toDouble(),
        )
        if (type == InAppMessageType.HTML && content.html == null) return null
        val s = o.obj("style") ?: JSONObject()
        return InAppMessage(
            id = id,
            type = type,
            priority = o.num("priority")?.toInt() ?: 0,
            trigger = trigger,
            reEligibility = ReEligibility(mode, hours),
            expiresAt = parseIso(o.str("expiresAt")),
            content = content,
            style = InAppStyle(
                backgroundColor = s.str("backgroundColor"),
                textColor = s.str("textColor"),
                buttonColor = s.str("buttonColor"),
                buttonTextColor = s.str("buttonTextColor"),
                cornerRadius = s.num("cornerRadius")?.toDouble(),
            ),
            decisionId = o.str("decisionId"),
        )
    }

    private fun parseTrigger(t: JSONObject): InAppTrigger? {
        val type = enumOf<TriggerType>(t.str("type")) { it.wire } ?: return null
        val event = t.str("event")
        if (type == TriggerType.EVENT && event == null) return null
        // An unknown operator voids the message rather than matching too broadly.
        val filters = t.arr("propertyFilters")?.let { a ->
            (0 until a.length()).map { i ->
                val f = a.getJSONObject(i)
                PropertyFilter(
                    key = f.str("key") ?: return null,
                    op = enumOf<FilterOperator>(f.str("op")) { it.wire } ?: return null,
                    value = fromJsonValue(f.opt("value")),
                )
            }
        } ?: emptyList()
        return InAppTrigger(type, event, filters)
    }

    private fun parseButton(b: JSONObject): InAppButton? {
        val action = b.obj("action")
        return InAppButton(
            id = b.str("id") ?: return null,
            text = b.str("text") ?: return null,
            action = ButtonAction(
                type = enumOf<ButtonActionType>(action?.str("type")) { it.wire } ?: ButtonActionType.CLOSE,
                value = action?.str("value"),
            ),
        )
    }
}
