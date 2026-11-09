package com.herald.sdk.internal

import com.herald.sdk.ContentCard
import com.herald.sdk.HeraldOptions
import com.herald.sdk.InAppDisplayDecision
import com.herald.sdk.InAppMessage
import com.herald.sdk.InAppMessageDelegate
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
    val engine = InAppEngine(clock, options.inAppMinimumIntervalSeconds * 1000, state.lastShown) {
        state.userId ?: InAppEngine.ANONYMOUS
    }

    /**
     * Hands a message to the presenter. Returns false when nothing can draw it right now; otherwise the
     * facade later reports [presentAccepted] or [presentFailed]. Set by the facade.
     */
    var display: ((InAppMessage) -> Boolean)? = null

    /** Asks the presenter to take down the message on screen (user switch). Set by the facade. */
    var dismissDisplay: (() -> Unit)? = null
    var delegate: () -> InAppMessageDelegate? = { null }

    /** Messages the delegate answered LATER for; shown only through [showNext]. */
    private val held = ArrayDeque<InAppMessage>()
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
        if (userId.isBlank()) return reject("identify", "userId must not be blank")
        Validation.length("userId", userId, 1, Validation.MAX_USER_ID)?.let { return reject("identify", it) }
        Validation.attributes("traits", traits)?.let { return reject("identify", it) }
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

    /** Binds [token] to the current user. Call it after [identify]: identifying a different user clears it. */
    fun setUserToken(token: String?) {
        state.userToken = token
        state.save()
        // Events this user queued before the token arrived would otherwise be sent without it.
        val uid = state.userId
        if (token != null && uid != null) queue.assignToken(uid, token)
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
        // lastShown stays: display history is keyed per user and only wipeData clears it.
        state.undeliveredIds.clear()
        engine.messages = emptyList()
        held.clear()
        impressedThisSession.clear()
        contentCards.value = emptyList()
        if (engine.onScreen != null) {
            engine.markDismissed()
            dismissDisplay?.invoke()
        }
    }

    fun setEnabled(enabled: Boolean) {
        state.enabled = enabled
        state.save()
    }

    fun wipeData() {
        queue.clear()
        clearUserState()
        state.delete()
        state.lastShown.clear()
        state.recentPushOpens.clear()
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
        Validation.length("event name", name, 1, Validation.MAX_NAME)?.let { return reject("track", it) }
        Validation.properties("properties", properties)?.let { return reject("track", it) }
        val props = jsonOrNull(properties, "track properties") ?: return
        enqueue("track") { put("name", name).put("properties", props) }
        trigger(TriggerEvent(TriggerType.EVENT, name, properties))
    }

    fun screen(name: String, properties: Map<String, Any?>) {
        Validation.length("screen name", name, 1, Validation.MAX_NAME)?.let { return reject("screen", it) }
        Validation.properties("properties", properties)?.let { return reject("screen", it) }
        val props = jsonOrNull(properties, "screen properties") ?: return
        enqueue("screen") { put("name", name).put("properties", props) }
    }

    fun trackPurchase(productId: String, price: Double, currency: String, quantity: Int, properties: Map<String, Any?>) {
        Validation.length("productId", productId, 1, Validation.MAX_NAME)?.let { return reject("trackPurchase", it) }
        if (currency.length != 3) return reject("trackPurchase", "currency must be a 3-letter ISO 4217 code")
        if (quantity < 1) return reject("trackPurchase", "quantity must be at least 1")
        if (!price.isFinite()) return reject("trackPurchase", "price is not finite")
        Validation.properties("properties", properties)?.let { return reject("trackPurchase", it) }
        val props = jsonOrNull(properties, "purchase properties") ?: return
        enqueue("purchase") {
            put("productId", productId).put("price", price).put("currency", currency)
                .put("quantity", quantity).put("properties", props)
        }
    }

    fun setAttributes(attributes: Map<String, Any?>) {
        if (attributes.isEmpty()) return
        Validation.attributes("attribute", attributes)?.let { return reject("setAttributes", it) }
        val json = jsonOrNull(attributes, "attributes") ?: return
        enqueue("attributes") { put("attributes", json) }
    }

    fun setSubscription(channel: SubscriptionChannel, subscriptionState: SubscriptionState) {
        enqueue("subscription") { put("channel", channel.wire).put("state", subscriptionState.wire) }
    }

    fun setPushToken(token: String) {
        if (token.isBlank()) return reject("setPushToken", "token must not be blank")
        Validation.length("token", token, 1, Validation.MAX_PUSH_TOKEN)?.let { return reject("setPushToken", it) }
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

    private fun reject(call: String, problem: String) = HeraldLog.warn("$call ignored: $problem")

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
        .put("events", JSONArray(orderForWire(batch.map { it.event })))
        .toString()

    /**
     * Wire rule: within a batch, push_token comes before any push subscription event, so the server
     * has the token when it applies the opt-in. Moves later push_tokens to just before the first one.
     */
    private fun orderForWire(events: List<JSONObject>): List<JSONObject> {
        val firstSubscription = events.indexOfFirst { it.optString("type") == "subscription" && it.optString("channel") == "push" }
        if (firstSubscription < 0) return events
        val (lateTokens, rest) = events.withIndex().partition { (i, e) -> i > firstSubscription && e.optString("type") == "push_token" }
        if (lateTokens.isEmpty()) return events
        val result = rest.map { it.value }.toMutableList()
        result.addAll(firstSubscription, lateTokens.map { it.value })
        return result
    }

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
        val ids = payload.inAppMessages.map { it.id }.toSet()
        if (state.undeliveredIds.retainAll(ids)) state.save()
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
        Validation.length("messageId", messageId, 1, Validation.MAX_MESSAGE_ID)?.let { return reject("interaction", it) }
        if (buttonId != null) Validation.length("buttonId", buttonId, 1, Validation.MAX_MESSAGE_ID)?.let { return reject("interaction", it) }
        val decision = decisionId?.takeIf { it.isNotEmpty() }
        if (decision != null) Validation.length("decisionId", decision, 1, Validation.MAX_MESSAGE_ID)?.let { return reject("interaction", it) }
        enqueue("interaction") {
            put("messageId", messageId).put("kind", kind).put("action", action)
                .putOpt("decisionId", decision).putOpt("buttonId", buttonId)
        }
    }

    // endregion

    // region in-app

    fun trigger(event: TriggerEvent) {
        if (!state.enabled) return
        val message = engine.evaluate(event) ?: return
        val decision = try {
            delegate()?.shouldDisplay(message) ?: InAppDisplayDecision.DISPLAY
        } catch (e: Exception) {
            HeraldLog.error("InAppMessageDelegate threw; displaying", e)
            InAppDisplayDecision.DISPLAY
        }
        when (decision) {
            InAppDisplayDecision.DISPLAY -> present(message)
            InAppDisplayDecision.LATER -> hold(message)
            InAppDisplayDecision.DISCARD -> HeraldLog.debug("In-app message ${message.id} discarded by delegate")
        }
    }

    private fun hold(message: InAppMessage) {
        held.removeAll { it.id == message.id }
        held.addLast(message)
        while (held.size > MAX_HELD) held.removeFirst()
    }

    /** Displays the most recently held message, if nothing is on screen. Skips the delegate and interval. */
    fun showNext(): Boolean {
        if (engine.onScreen != null) return false
        while (held.isNotEmpty()) {
            val message = held.removeLast()
            if (engine.isLive(message)) {
                present(message)
                return true
            }
        }
        return false
    }

    /**
     * Retries triggered messages that could not be drawn (no activity or presenter at the time).
     * The facade calls it on every activity resume. Still one message at a time, after the interval.
     */
    fun retryUndelivered() {
        if (!state.enabled || engine.onScreen != null || !engine.intervalElapsed()) return
        val candidates = state.undeliveredIds.mapNotNull { id -> engine.messages.firstOrNull { it.id == id } }
        val live = candidates.filter { engine.isLive(it) && engine.isEligible(it) }
        if (live.size != state.undeliveredIds.size) {
            state.undeliveredIds.retainAll(live.map { it.id }.toSet())
            state.save()
        }
        live.maxByOrNull { it.priority }?.let(::present)
    }

    private fun present(message: InAppMessage) {
        engine.beginPresenting(message)
        clickedOnScreen = false
        val handedOver = try {
            display?.invoke(message) ?: false
        } catch (e: Exception) {
            HeraldLog.error("In-app display failed", e)
            false
        }
        if (!handedOver) presentFailed(message)
    }

    /** The presenter showed the message: only now is it marked shown and the interval started. */
    fun presentAccepted(message: InAppMessage) {
        // Stale confirmation (reset or user switch since the hand-off): ignore.
        if (engine.onScreen?.id != message.id) return
        engine.recordShown(message)
        state.undeliveredIds.remove(message.id)
        state.save()
    }

    /** Nothing could draw the message: free the screen and keep it for the next activity resume. */
    fun presentFailed(message: InAppMessage) {
        if (engine.onScreen?.id != message.id) return
        engine.markDismissed()
        if (message.id !in state.undeliveredIds) {
            state.undeliveredIds.add(message.id)
            state.save()
        }
    }

    /**
     * The activity showing the message was destroyed before the user dismissed it (rotation, finish):
     * free the screen, log nothing, undo the display and keep the message for the next activity resume.
     */
    fun presenterGone(message: InAppMessage) {
        if (engine.onScreen?.id != message.id) return
        engine.markDismissed()
        engine.revertShown(message)
        if (message.id !in state.undeliveredIds) state.undeliveredIds.add(message.id)
        state.save()
    }

    /** Debug and preview only: replaces the cards and in-app messages in memory, without persisting. */
    fun debugLoadMessages(body: String): Boolean {
        val payload = MessageParser.parse(body) ?: return false
        apply(payload)
        return true
    }

    /** Debug and preview only: presents a loaded in-app message now, skipping triggers, eligibility and interval. */
    fun debugShowInAppMessage(messageId: String): Boolean {
        if (engine.onScreen != null) return false
        val message = engine.messages.firstOrNull { it.id == messageId } ?: return false
        present(message)
        return true
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

    val heldCount: Int get() = held.size
    val undeliveredIds: List<String> get() = state.undeliveredIds.toList()

    // endregion

    // region push

    fun onPushOpened(messageId: String, decisionId: String?) {
        // The system redelivers the original intent extras after recreation; count each decision's open once.
        // Without a decisionId there is no per-send key (a messageId can repeat across sends), so no dedupe.
        if (decisionId != null) {
            if (decisionId in state.recentPushOpens) return
            state.recentPushOpens.add(decisionId)
            while (state.recentPushOpens.size > MAX_RECENT_OPENS) state.recentPushOpens.removeAt(0)
            state.save()
        }
        logInteraction(messageId, "push", "open", decisionId)
        if (foreground) trigger(TriggerEvent(TriggerType.PUSH_OPEN)) else pushOpenTriggerDue = true
    }

    fun setLocation(latitude: Double, longitude: Double) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return HeraldLog.warn("setLocation ignored: out of range")
        setAttributes(mapOf("\$latitude" to latitude, "\$longitude" to longitude))
    }

    // endregion

    companion object {
        const val SDK_NAME = "herald-android"
        const val QUEUE_CAP = 1000
        const val MAX_BATCH_EVENTS = 100
        const val MAX_BATCH_BYTES = 500_000
        const val MAX_HELD = 20
        const val MAX_RECENT_OPENS = 50
    }
}
