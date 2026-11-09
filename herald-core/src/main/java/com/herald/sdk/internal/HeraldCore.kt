package com.herald.sdk.internal

import com.herald.sdk.ContentCard
import com.herald.sdk.HeraldOptions
import com.herald.sdk.InAppDisplayDecision
import com.herald.sdk.InAppMessage
import com.herald.sdk.InAppMessageListener
import com.herald.sdk.SubscriptionChannel
import com.herald.sdk.SubscriptionState
import com.herald.sdk.TriggerType
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import kotlin.random.Random

/**
 * All SDK state and rules. Not thread-safe by design: the [com.herald.sdk.Herald] facade calls it
 * from one serial dispatcher, and tests call it directly.
 */
internal class HeraldCore(
    dir: File,
    private val appKey: String,
    endpoint: String,
    private val options: HeraldOptions,
    private val transport: Transport,
    private val contextProvider: () -> DeviceContext,
    private val sdkVersion: String,
    private val bundleId: String? = null,
    private val contentCards: MutableStateFlow<List<ContentCard>> = MutableStateFlow(emptyList()),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val endpoint = endpoint.trimEnd('/')
    private val state = StateStore(File(dir, "herald_state.json"))
    val queue = EventQueue(File(dir, "herald_queue.json"), QUEUE_CAP)
    val engine = InAppEngine(clock, options.inAppMinimumIntervalSeconds * 1000, state.lastShown)

    /** Shows a message; returns false when nothing can draw it right now. Set by the facade. */
    var display: ((InAppMessage) -> Boolean)? = null
    var listener: () -> InAppMessageListener? = { null }

    private val pending = ArrayDeque<InAppMessage>()
    private val impressedThisSession = mutableSetOf<String>()
    private var clickedOnScreen = false
    private var foreground = false
    private var sessionTriggerDue = false
    private var pushOpenTriggerDue = false
    private var failures = 0
    var nextAttemptAt = 0L
        private set

    val userId: String? get() = state.userId
    val deviceId: String get() = state.deviceId!!
    val enabled: Boolean get() = state.enabled

    init {
        if (state.deviceId == null) {
            state.deviceId = newId()
            state.save()
        }
        state.messagesBody?.let(MessageParser::parse)?.let(::apply)
    }

    // region lifecycle

    fun onForeground() {
        ensureSession(clock())
        foreground = true
        if (pushOpenTriggerDue) {
            pushOpenTriggerDue = false
            trigger(TriggerEvent(TriggerType.PUSH_OPEN))
        }
        if (sessionTriggerDue) {
            sessionTriggerDue = false
            trigger(TriggerEvent(TriggerType.SESSION_START))
        }
    }

    fun onBackground() {
        foreground = false
        state.lastActivity = clock()
        state.save()
        flush()
    }

    private fun ensureSession(now: Long) {
        if (!state.enabled) return
        val expired = state.sessionId == null ||
            (!foreground && now - state.lastActivity > options.sessionTimeoutMinutes * 60_000)
        if (!expired) return
        if (state.sessionId != null) {
            val duration = (state.lastActivity - state.sessionStart).coerceAtLeast(0) / 1000
            enqueueRaw("session_end", now) { put("durationSeconds", duration) }
        }
        state.sessionId = now.toString()
        state.sessionStart = now
        state.lastActivity = now
        state.save()
        impressedThisSession.clear()
        enqueueRaw("session_start", now) {}
        syncMessages()
        if (foreground) trigger(TriggerEvent(TriggerType.SESSION_START)) else sessionTriggerDue = true
    }

    // endregion

    // region identity

    fun identify(userId: String, traits: Map<String, Any?>) {
        if (userId.isBlank()) return HeraldLog.warn("identify ignored: blank userId")
        val traitsJson = jsonOrNull(traits, "identify traits") ?: return
        val changed = state.userId != userId
        if (changed && state.userId != null) clearUserState()
        state.userId = userId
        state.save()
        enqueue("identify") { put("userId", userId).put("traits", traitsJson) }
        if (changed) {
            state.pushToken?.let(::enqueuePushToken)
            syncMessages()
        }
    }

    fun setUserToken(token: String?) {
        state.userToken = token
        state.save()
    }

    fun reset() {
        clearUserState()
        state.save()
    }

    private fun clearUserState() {
        state.userId = null
        state.userToken = null
        state.etag = null
        state.messagesBody = null
        state.dismissedCardIds.clear()
        state.lastShown.clear()
        engine.messages = emptyList()
        pending.clear()
        impressedThisSession.clear()
        contentCards.value = emptyList()
    }

    fun setEnabled(enabled: Boolean) {
        state.enabled = enabled
        state.save()
    }

    fun wipeData() {
        queue.clear()
        clearUserState()
        state.delete()
        state.deviceId = newId()
        state.pushToken = null
        state.sessionId = null
        state.sessionStart = 0
        state.lastActivity = 0
        state.save()
        failures = 0
        nextAttemptAt = 0
    }

    // endregion

    // region events

    fun track(name: String, properties: Map<String, Any?>) {
        if (name.isBlank()) return HeraldLog.warn("track ignored: blank event name")
        val props = jsonOrNull(properties, "track properties") ?: return
        enqueue("track") { put("name", name).put("properties", props) }
        trigger(TriggerEvent(TriggerType.EVENT, name, properties))
    }

    fun screen(name: String, properties: Map<String, Any?>) {
        val props = jsonOrNull(properties, "screen properties") ?: return
        enqueue("screen") { put("name", name).put("properties", props) }
    }

    fun trackPurchase(productId: String, price: Double, currency: String, quantity: Int, properties: Map<String, Any?>) {
        if (!price.isFinite()) return HeraldLog.warn("trackPurchase ignored: price is not finite")
        val props = jsonOrNull(properties, "purchase properties") ?: return
        enqueue("purchase") {
            put("productId", productId).put("price", price).put("currency", currency)
                .put("quantity", quantity).put("properties", props)
        }
    }

    fun setAttributes(attributes: Map<String, Any?>) {
        val json = JSONObject()
        attributes.forEach { (key, value) ->
            if (key.isEmpty() || key.length > 255) {
                HeraldLog.warn("Attribute key must be 1-255 characters; skipped")
            } else if (!isValidAttributeValue(value)) {
                HeraldLog.warn("Attribute '$key' has an unsupported value type; skipped")
            } else {
                json.put(key, toJsonValue(value))
            }
        }
        if (json.length() > 0) enqueue("attributes") { put("attributes", json) }
    }

    fun setSubscription(channel: SubscriptionChannel, subscriptionState: SubscriptionState) {
        enqueue("subscription") { put("channel", channel.wire).put("state", subscriptionState.wire) }
    }

    fun setPushToken(token: String) {
        if (token.isBlank()) return
        state.pushToken = token
        state.save()
        enqueuePushToken(token)
    }

    private fun enqueuePushToken(token: String) {
        enqueue("push_token") { put("platform", "fcm").put("token", token).putOpt("bundleId", bundleId) }
    }

    private fun enqueue(type: String, fields: JSONObject.() -> Unit) {
        if (!state.enabled) return
        val now = clock()
        ensureSession(now)
        enqueueRaw(type, now, fields)
    }

    private fun enqueueRaw(type: String, now: Long, fields: JSONObject.() -> Unit) {
        val event = JSONObject()
            .put("id", newId())
            .put("type", type)
            .put("timestamp", isoTimestamp(now))
            .put("sessionId", state.sessionId)
        event.fields()
        queue.add(QueuedEvent(state.userId, state.userToken, event))
        state.lastActivity = now
        if (queue.size >= options.flushAt) flush()
    }

    private fun jsonOrNull(map: Map<String, Any?>, what: String): JSONObject? = try {
        toJsonValue(map) as JSONObject
    } catch (e: IllegalArgumentException) {
        HeraldLog.warn("$what contain an unsupported value (${e.message}); call ignored")
        null
    }

    private fun isValidAttributeValue(value: Any?): Boolean = when (value) {
        null, is String, is Boolean -> true
        is Number -> value.toDouble().isFinite()
        is Collection<*> -> value.all { it is String }
        is Array<*> -> value.all { it is String }
        else -> false
    }

    // endregion

    // region delivery

    fun flush() {
        if (!state.enabled) return
        while (true) {
            if (clock() < nextAttemptAt) return
            var batch = queue.peekBatch(MAX_BATCH_EVENTS)
            if (batch.isEmpty()) return
            val dropped = queue.dropped
            var body = encodeBatch(batch, dropped)
            while (body.toByteArray().size > MAX_BATCH_BYTES && batch.size > 1) {
                batch = batch.take(batch.size / 2)
                body = encodeBatch(batch, dropped)
            }
            val batchUser = batch.first().userId
            val token = if (batchUser != null && batchUser == state.userId) {
                state.userToken ?: batch.lastOrNull { it.userToken != null }?.userToken
            } else {
                batch.lastOrNull { it.userToken != null }?.userToken
            }
            val headers = buildMap {
                put("Content-Type", "application/json")
                put("x-herald-app-key", appKey)
                if (batchUser != null && token != null) put("x-herald-user-token", token)
            }
            val response = try {
                transport.execute(HttpRequest("POST", "$endpoint/api/v1/sdk/batch", headers, body))
            } catch (e: Exception) {
                HeraldLog.info("Batch send failed (${e.javaClass.simpleName}); will retry")
                scheduleRetry(null)
                return
            }
            when (response.status) {
                in 200..299 -> {
                    failures = 0
                    nextAttemptAt = 0
                    queue.removeFirst(batch.size, dropped)
                    HeraldLog.debug("Sent ${batch.size} events")
                }
                408, 429, in 500..599 -> {
                    HeraldLog.info("Batch send got ${response.status}; will retry")
                    scheduleRetry(Backoff.retryAfterSeconds(response))
                    return
                }
                else -> {
                    // 400/401/413 and other client errors never succeed on retry.
                    HeraldLog.error("Herald rejected a batch (${response.status}): ${response.body.take(300)}. Dropping ${batch.size} events.")
                    queue.removeFirst(batch.size, dropped)
                }
            }
        }
    }

    private fun scheduleRetry(retryAfterSeconds: Long?) {
        failures++
        nextAttemptAt = clock() + Backoff.delayMs(failures, retryAfterSeconds, random)
    }

    fun encodeBatch(batch: List<QueuedEvent>, dropped: Int): String = JSONObject()
        .put("sdk", JSONObject().put("name", SDK_NAME).put("version", sdkVersion))
        .put("deviceId", deviceId)
        .put("userId", batch.firstOrNull()?.userId ?: JSONObject.NULL)
        .put("sentAt", isoTimestamp(clock()))
        .put("dropped", dropped)
        .put("context", contextProvider().toJson(options.deviceDataCollection))
        .put("events", JSONArray(batch.map { it.event }))
        .toString()

    // endregion

    // region messages

    fun syncMessages() {
        val uid = state.userId ?: return
        if (!state.enabled) return
        val url = "$endpoint/api/v1/sdk/messages?deviceId=${encode(deviceId)}&userId=${encode(uid)}"
        val headers = buildMap {
            put("x-herald-app-key", appKey)
            state.userToken?.let { put("x-herald-user-token", it) }
            state.etag?.let { put("If-None-Match", it) }
        }
        val response = try {
            transport.execute(HttpRequest("GET", url, headers))
        } catch (e: Exception) {
            return HeraldLog.info("Message sync failed (${e.javaClass.simpleName}); using cached messages")
        }
        when (response.status) {
            304 -> HeraldLog.debug("Messages unchanged")
            200 -> {
                val payload = MessageParser.parse(response.body)
                    ?: return HeraldLog.warn("Message sync returned an unreadable body")
                state.etag = response.header("ETag")
                state.messagesBody = response.body
                state.save()
                apply(payload)
            }
            else -> HeraldLog.warn("Message sync got ${response.status}: ${response.body.take(300)}")
        }
    }

    private fun apply(payload: MessagesPayload) {
        val now = clock()
        contentCards.value = payload.contentCards.filter {
            !it.dismissed && it.id !in state.dismissedCardIds && (it.expiresAt == null || it.expiresAt > now)
        }
        engine.messages = payload.inAppMessages
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    // endregion

    // region content cards

    fun logCardImpression(cardId: String) {
        val card = updateCard(cardId) { it.copy(viewed = true) } ?: return
        if (impressedThisSession.add(cardId)) logInteraction(card.id, "content_card", "impression", card.decisionId)
    }

    fun logCardClick(cardId: String) {
        val card = updateCard(cardId) { it.copy(clicked = true) } ?: return
        logInteraction(card.id, "content_card", "click", card.decisionId)
    }

    fun logCardDismissal(cardId: String) {
        val card = contentCards.value.firstOrNull { it.id == cardId } ?: return
        state.dismissedCardIds.add(cardId)
        state.save()
        contentCards.value = contentCards.value.filterNot { it.id == cardId }
        logInteraction(card.id, "content_card", "dismiss", card.decisionId)
    }

    private fun updateCard(cardId: String, change: (ContentCard) -> ContentCard): ContentCard? {
        val card = contentCards.value.firstOrNull { it.id == cardId }
            ?: return null.also { HeraldLog.warn("Unknown content card $cardId") }
        contentCards.value = contentCards.value.map { if (it.id == cardId) change(it) else it }
        return card
    }

    private fun logInteraction(messageId: String, kind: String, action: String, decisionId: String?, buttonId: String? = null) {
        enqueue("interaction") {
            put("messageId", messageId).put("kind", kind).put("action", action)
                .putOpt("decisionId", decisionId).putOpt("buttonId", buttonId)
        }
    }

    // endregion

    // region in-app

    fun trigger(event: TriggerEvent) {
        if (!state.enabled) return
        val message = engine.evaluate(event) ?: return
        val decision = try {
            listener()?.shouldDisplay(message) ?: InAppDisplayDecision.DISPLAY
        } catch (e: Exception) {
            HeraldLog.error("InAppMessageListener threw; displaying", e)
            InAppDisplayDecision.DISPLAY
        }
        when (decision) {
            InAppDisplayDecision.DISPLAY -> show(message)
            InAppDisplayDecision.LATER -> pending.addLast(message)
            InAppDisplayDecision.DISCARD -> HeraldLog.debug("In-app message ${message.id} discarded by listener")
        }
    }

    /** Displays the most recently held message, if nothing is on screen. Skips the listener and interval. */
    fun showNext(): Boolean {
        if (engine.onScreen != null) return false
        while (pending.isNotEmpty()) {
            val message = pending.removeLast()
            if (engine.isLive(message)) {
                show(message)
                return true
            }
        }
        return false
    }

    private fun show(message: InAppMessage) {
        engine.markDisplayed(message)
        state.save()
        clickedOnScreen = false
        val shown = try {
            display?.invoke(message) ?: false
        } catch (e: Exception) {
            HeraldLog.error("In-app display failed", e)
            false
        }
        if (!shown) presentFailed(message)
    }

    /** No presenter or activity could take the message: free the screen and hold it for [showNext]. */
    fun presentFailed(message: InAppMessage) {
        if (engine.onScreen?.id == message.id) engine.markDismissed()
        pending.addLast(message)
    }

    fun onInAppImpression(message: InAppMessage) =
        logInteraction(message.id, "in_app", "impression", message.decisionId)

    fun onInAppClick(message: InAppMessage, buttonId: String?) {
        clickedOnScreen = true
        if (buttonId != null) {
            logInteraction(message.id, "in_app", "button", message.decisionId, buttonId)
        } else {
            logInteraction(message.id, "in_app", "click", message.decisionId)
        }
    }

    fun onInAppDismiss(message: InAppMessage) {
        if (engine.onScreen?.id != message.id) return
        engine.markDismissed()
        if (!clickedOnScreen) logInteraction(message.id, "in_app", "dismiss", message.decisionId)
    }

    val pendingCount: Int get() = pending.size

    // endregion

    // region push

    fun onPushOpened(messageId: String, decisionId: String?) {
        logInteraction(messageId, "push", "open", decisionId)
        if (foreground) trigger(TriggerEvent(TriggerType.PUSH_OPEN)) else pushOpenTriggerDue = true
    }

    fun setLocation(latitude: Double, longitude: Double) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return HeraldLog.warn("setLocation ignored: out of range")
        setAttributes(mapOf("latitude" to latitude, "longitude" to longitude))
    }

    // endregion

    companion object {
        const val SDK_NAME = "herald-android"
        const val QUEUE_CAP = 1000
        const val MAX_BATCH_EVENTS = 100
        const val MAX_BATCH_BYTES = 500_000
    }
}
