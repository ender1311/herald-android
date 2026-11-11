package com.herald.sdk.internal

import com.herald.sdk.ContentCard
import com.herald.sdk.HeraldOptions
import com.herald.sdk.InAppDisplayDecision
import com.herald.sdk.InAppMessage
import com.herald.sdk.InAppMessageDelegate
import com.herald.sdk.SubscriptionChannel
import com.herald.sdk.SubscriptionState
import com.herald.sdk.TriggerType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread
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
    /** Where the in-app delegate is called (the main thread in the app); null calls it inline. */
    private val mainDispatcher: CoroutineDispatcher? = null,
    /** Runs the one-off push unsubscribe off the SDK thread; tests run it inline. */
    private val background: (() -> Unit) -> Unit = { work -> thread(isDaemon = true, name = "herald-unsubscribe") { work() } },
) {
    private val endpoint = endpoint.trimEnd('/')
    private val state = StateStore(File(dir, StateStore.FILE_NAME))
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

    /** Asks the facade to call [showPendingIfPossible] after this many ms (the minimum interval's rest). */
    var scheduleShowRetry: ((Long) -> Unit)? = null

    /** Asks the facade to call [refreshCards] after this many ms (when the soonest card expires); null cancels. */
    var scheduleCardRefresh: ((Long?) -> Unit)? = null

    /** Asks for the current push token again (herald-fcm fetches it and calls [setPushToken]). Set by the facade. */
    var requestPushToken: (() -> Unit)? = null
    private var clickedOnScreen = false
    private var foreground = false
    private var sessionTriggerDue = false
    private var pushOpenTriggerDue = false
    private var failures = 0

    /** Users whose batch got 401: their events stay queued and unsent until [setUserToken]. */
    private val usersAwaitingToken = mutableSetOf<String>()

    /** The server refused the app key: nothing is sent (and nothing dropped) until the next launch. */
    private var appKeyRejected = false
    private var activityPersistedAt = 0L

    /** When the last sync push refreshed; one within [SYNC_PUSH_DEBOUNCE_MS] of it is skipped. */
    private var lastSyncPushAt: Long? = null
    var nextAttemptAt = 0L
        private set

    val userId: String? get() = state.userId
    /** Set whenever the SDK is enabled; null only after [wipeData] until [setEnabled] (true). */
    val deviceId: String get() = state.deviceId!!
    val enabled: Boolean get() = state.enabled

    init {
        ensureDeviceId()
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
        showPendingIfPossible()
    }

    fun onBackground() {
        foreground = false
        if (!state.enabled) return
        state.lastActivity = clock()
        state.save()
        flush()
    }

    /**
     * Continues the current session or, after `sessionTimeoutMinutes` without activity (any recorded call or
     * foreground, in the foreground or background), ends it and starts a new one. Same rule as iOS.
     */
    private fun ensureSession(now: Long) {
        if (!state.enabled) return
        if (state.sessionId != null) {
            if (now - state.lastActivity < options.sessionTimeoutMinutes * 60_000) {
                state.lastActivity = now
                // Activity is persisted at most once a minute (and on background), as on iOS.
                if (now - activityPersistedAt >= 60_000) {
                    activityPersistedAt = now
                    state.save()
                }
                return
            }
            enqueueSessionEnd()
        }
        activityPersistedAt = now
        state.sessionId = now.toString()
        state.sessionStart = now
        state.lastActivity = now
        state.impressedCardIds.clear()
        state.save()
        enqueueRaw("session_start", now) {}
        syncMessages()
        if (foreground) trigger(TriggerEvent(TriggerType.SESSION_START)) else sessionTriggerDue = true
    }

    /** Queues session_end for the current session, stamped at its last activity. */
    private fun enqueueSessionEnd() {
        val duration = (state.lastActivity - state.sessionStart).coerceAtLeast(0) / 1000
        enqueueRaw("session_end", state.lastActivity) { put("durationSeconds", duration) }
    }

    // endregion

    // region identity

    fun identify(userId: String, traits: Map<String, Any?>) {
        if (!state.enabled) return
        if (userId.isBlank()) return reject("identify", "userId must not be blank")
        Validation.length("userId", userId, 1, Validation.MAX_USER_ID)?.let { return reject("identify", it) }
        Validation.attributes("traits", traits)?.let { return reject("identify", it) }
        val traitsJson = jsonOrNull(traits, "identify traits") ?: return
        val previous = state.userId
        val changed = previous != userId
        if (changed && previous != null) clearUserState()
        state.userId = userId
        state.save()
        enqueue("identify") { put("userId", userId).put("traits", traitsJson) }
        if (changed) state.pushToken?.let(::enqueuePushToken)
        // Same session, now identified: fetch this user's messages. A switch starts a session, which syncs.
        if (previous == null) syncMessages()
    }

    /** Binds [token] to the current user. Call it after [identify]: identifying a different user clears it. */
    fun setUserToken(token: String?) {
        if (!state.enabled) return
        val changed = state.userToken != token
        state.userToken = token
        state.save()
        // Events this user queued before the token arrived would otherwise be sent without it.
        val uid = state.userId
        if (token != null && uid != null) {
            queue.assignToken(uid, token)
            // identify() already synced without the token, which a server requiring verification refuses.
            if (changed) syncMessages()
        }
        if (usersAwaitingToken.isNotEmpty()) {
            usersAwaitingToken.clear()
            flush()
        }
    }

    /** Back to an anonymous user on the same device, with a new session; the push token is sent again. */
    fun reset() {
        clearUserState()
        state.save()
        if (!state.enabled) return
        ensureSession(clock())
        state.pushToken?.let(::enqueuePushToken)
    }

    /** Ends the session (so the next event starts a new one) and forgets the current user. */
    private fun clearUserState() {
        if (state.sessionId != null) {
            // Queued before the user id is cleared, so it belongs to the user whose session it ends.
            if (state.enabled) enqueueSessionEnd()
            state.sessionId = null
        }
        state.deferredPushSubscription = null
        state.userId = null
        state.userToken = null
        state.etag = null
        state.messagesBody = null
        state.dismissedCardIds.clear()
        // lastShown stays: display history is keyed per user and only wipeData clears it.
        state.undeliveredIds.clear()
        engine.messages = emptyList()
        state.impressedCardIds.clear()
        publishCards(emptyList())
        if (engine.onScreen != null) {
            engine.markDismissed()
            dismissDisplay?.invoke()
        }
    }

    fun setEnabled(enabled: Boolean) {
        val wasEnabled = state.enabled
        if (!enabled) sendPushUnsubscribe()
        state.enabled = enabled
        state.save()
        if (!enabled) {
            publishCards(emptyList()) // hidden until enabled again
            return
        }
        ensureDeviceId()
        state.messagesBody?.let(MessageParser::parse)?.let(::apply)
        if (foreground) onForeground() // start the session now, as iOS does
        if (!wasEnabled) restorePushIfAuthorized()
    }

    /**
     * Undoes the unsubscribe sent when the SDK was turned off: when notifications are enabled, queues the push
     * token and a `subscription push subscribed` for the next batch. With no stored token (after [wipeData]) it
     * asks for the token again; the subscription waits for it, as any push opt-in does. Same rule as iOS.
     */
    private fun restorePushIfAuthorized() {
        if (contextProvider().pushAuthorization != "authorized") return
        val token = state.pushToken
        if (token != null) enqueuePushToken(token)
        setSubscription(SubscriptionChannel.PUSH, SubscriptionState.SUBSCRIBED)
        if (token == null) requestPushToken?.invoke()
    }

    /**
     * Before the SDK goes quiet, tells Herald to stop pushing to this device: one `subscription push
     * unsubscribed` batch, sent once off the SDK thread and never queued or retried (best effort).
     */
    private fun sendPushUnsubscribe() {
        if (!state.enabled || state.pushToken == null) return
        val now = clock()
        val event = JSONObject()
            .put("id", newId())
            .put("type", "subscription")
            .put("timestamp", isoTimestamp(now))
            .put("sessionId", state.sessionId)
            .put("channel", SubscriptionChannel.PUSH.wire)
            .put("state", SubscriptionState.UNSUBSCRIBED.wire)
        val uid = state.userId
        // Built now: wipeData() forgets the device id and token right after this returns.
        val body = encodeBatch(listOf(QueuedEvent(uid, state.userToken, event)), 0)
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("x-herald-app-key", appKey)
            if (uid != null) state.userToken?.let { put("x-herald-user-token", it) }
        }
        val request = HttpRequest("POST", "$endpoint/api/v1/sdk/batch", headers, body)
        background {
            try {
                transport.execute(request)
            } catch (e: Exception) {
                HeraldLog.info("Push unsubscribe not sent (${e.javaClass.simpleName})")
            }
        }
    }

    /** A disabled SDK after [wipeData] keeps no device id on disk; a new one is made when it is enabled again. */
    private fun ensureDeviceId() {
        if (state.deviceId != null || !state.enabled) return
        state.deviceId = newId()
        state.save()
    }

    /**
     * Deletes every file and in-memory record and leaves the SDK disabled until `setEnabled(true)` (a wipe
     * usually means consent was withdrawn), as on iOS. Re-enabling starts with a new device id.
     */
    fun wipeData() {
        sendPushUnsubscribe()
        state.sessionId = null // no session_end: everything is being deleted
        queue.clear()
        clearUserState()
        state.lastShown.clear()
        state.recentPushOpens.clear()
        state.deviceId = null
        state.pushToken = null
        state.sessionStart = 0
        state.lastActivity = 0
        state.userTokenRequired = false
        state.enabled = false
        state.delete()
        failures = 0
        nextAttemptAt = 0
        usersAwaitingToken.clear()
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
        val code = currency.uppercase(java.util.Locale.ROOT)
        if (code.length != 3) return reject("trackPurchase", "currency must be a 3-letter ISO 4217 code")
        if (quantity < 1) return reject("trackPurchase", "quantity must be at least 1")
        if (!price.isFinite()) return reject("trackPurchase", "price is not finite")
        Validation.properties("properties", properties)?.let { return reject("trackPurchase", it) }
        val props = jsonOrNull(properties, "purchase properties") ?: return
        enqueue("purchase") {
            put("productId", productId).put("price", price).put("currency", code)
                .put("quantity", quantity).put("properties", props)
        }
    }

    fun setAttributes(input: Map<String, Any?>) {
        if (!state.enabled) return
        val attributes = Validation.droppingInvalidContacts(input)
        if (attributes.isEmpty()) return
        Validation.attributes("attribute", attributes)?.let { return reject("setAttributes", it) }
        val json = jsonOrNull(attributes, "attributes") ?: return
        enqueue("attributes") { put("attributes", json) }
    }

    /**
     * A push opt-in made before any push token exists waits for the token, so the server sees the device's
     * push_token first. An opt-out is sent at once (a denied permission never yields a token) and cancels
     * any deferred opt-in. Same rule as iOS.
     */
    fun setSubscription(channel: SubscriptionChannel, subscriptionState: SubscriptionState) {
        if (!state.enabled) return
        if (channel == SubscriptionChannel.PUSH) {
            if (subscriptionState != SubscriptionState.UNSUBSCRIBED && state.pushToken == null) {
                state.deferredPushSubscription = subscriptionState.wire
                state.save()
                return
            }
            if (state.deferredPushSubscription != null) {
                state.deferredPushSubscription = null
                state.save()
            }
        }
        enqueue("subscription") { put("channel", channel.wire).put("state", subscriptionState.wire) }
    }

    fun setPushToken(token: String) {
        if (token.isBlank()) return reject("setPushToken", "token must not be blank")
        Validation.length("token", token, 1, Validation.MAX_PUSH_TOKEN)?.let { return reject("setPushToken", it) }
        if (!state.enabled) return
        state.pushToken = token
        state.save()
        enqueuePushToken(token)
        val deferred = SubscriptionState.entries.firstOrNull { it.wire == state.deferredPushSubscription } ?: return
        setSubscription(SubscriptionChannel.PUSH, deferred)
    }

    /** Result of the notification permission prompt: opted_in when granted, unsubscribed when denied. */
    fun recordPushAuthorization(granted: Boolean) =
        setSubscription(SubscriptionChannel.PUSH, if (granted) SubscriptionState.OPTED_IN else SubscriptionState.UNSUBSCRIBED)

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
        if (!state.enabled || appKeyRejected) return
        while (true) {
            if (clock() < nextAttemptAt) return
            var batch = queue.peekBatch(MAX_BATCH_EVENTS, usersAwaitingToken)
            if (batch.isEmpty()) return
            val dropped = queue.dropped
            var body = encodeBatch(batch, dropped)
            while (body.toByteArray().size > MAX_BATCH_BYTES && batch.size > 1) {
                batch = batch.take(batch.size / 2)
                body = encodeBatch(batch, dropped)
            }
            if (body.toByteArray().size > MAX_BATCH_BYTES) {
                // One event too large to ever send: drop it rather than block the queue (no 413 round trip).
                HeraldLog.warn("Dropping an event larger than ${MAX_BATCH_BYTES / 1024} KB")
                queue.remove(batch.take(1), 0)
                continue
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
                    queue.remove(batch, dropped)
                    HeraldLog.debug("Sent ${batch.size} events")
                }
                408, 429, in 500..599 -> {
                    HeraldLog.info("Batch send got ${response.status}; will retry")
                    scheduleRetry(Backoff.retryAfterSeconds(response, clock()))
                    return
                }
                else -> if (response.status == 401 && isAppKeyRejection(response.body)) {
                    // Retrying cannot help, but dropping would lose the events to a configuration mistake.
                    HeraldLog.error("Herald rejected the app key: check the publishable key in configure")
                    appKeyRejected = true
                    return
                } else if (response.status == 401 && batchUser != null) {
                    // Most likely identify() ran and setUserToken() has not yet: keep the events for the token.
                    if (usersAwaitingToken.isEmpty()) HeraldLog.warn("Herald needs a user token: call setUserToken")
                    usersAwaitingToken.add(batchUser)
                    state.userTokenRequired = true
                    state.save()
                    failures = 0
                    nextAttemptAt = 0
                } else {
                    // 400/401/413 and other client errors never succeed on retry.
                    HeraldLog.error("Herald rejected a batch (${response.status}): ${response.body.take(300)}. Dropping ${batch.size} events.")
                    queue.remove(batch, dropped)
                }
            }
        }
    }

    /** A 401 whose body is the server's `{"error":"Invalid app key"}` (src/lib/sdk/auth.ts). */
    private fun isAppKeyRejection(body: String): Boolean =
        runCatching { JSONObject(body).optString("error") == "Invalid app key" }.getOrDefault(false)

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

    /**
     * A Herald sync push: refreshes (with the usual ETag) unless another sync push did within
     * [SYNC_PUSH_DEBOUNCE_MS]. Never shows anything or logs a push interaction.
     */
    fun syncFromPush() {
        if (!state.enabled) return
        val now = clock()
        val last = lastSyncPushAt
        if (last != null && now >= last && now - last < SYNC_PUSH_DEBOUNCE_MS) {
            return HeraldLog.debug("Sync push skipped: another one refreshed less than 30 s ago")
        }
        lastSyncPushAt = now
        syncMessages()
    }

    /**
     * Anonymous users sync too (as on iOS); the server returns empty lists for them. Identified without a token
     * on a server known to require one, it waits for [setUserToken] (which syncs) instead of collecting a 401.
     */
    fun syncMessages() {
        if (!state.enabled) return
        val uid = state.userId
        val sentToken = if (uid != null) state.userToken else null
        if (uid != null && sentToken == null && state.userTokenRequired) return HeraldLog.debug("Message sync waits for setUserToken")
        val url = "$endpoint/api/v1/sdk/messages?deviceId=${encode(deviceId)}" + (uid?.let { "&userId=${encode(it)}" } ?: "")
        val headers = buildMap {
            put("x-herald-app-key", appKey)
            sentToken?.let { put("x-herald-user-token", it) }
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
                // Accepted without a token: verification is not required (any more).
                if (uid != null && sentToken == null) state.userTokenRequired = false
                state.save()
                apply(payload)
            }
            401 -> if (uid != null && !isAppKeyRejection(response.body)) {
                state.userTokenRequired = true
                state.save()
                // Expected before the first setUserToken, which syncs again: not an error then.
                if (sentToken == null) {
                    HeraldLog.debug("Message sync needs a user token; waiting for setUserToken")
                } else {
                    HeraldLog.warn("Message sync got 401: Herald refused the user token")
                }
            } else {
                HeraldLog.warn("Message sync got 401: ${response.body.take(300)}")
            }
            else -> HeraldLog.warn("Message sync got ${response.status}: ${response.body.take(300)}")
        }
    }

    private fun apply(payload: MessagesPayload) {
        // Cached cards stay hidden while the SDK is disabled.
        publishCards(if (!state.enabled) emptyList() else payload.contentCards.filter { !it.dismissed && it.id !in state.dismissedCardIds })
        engine.messages = payload.inAppMessages
        val ids = payload.inAppMessages.map { it.id }.toSet()
        if (state.undeliveredIds.retainAll(ids)) state.save()
    }

    /**
     * Every card list goes out through here: expired cards are dropped, and a refresh is scheduled for when the
     * soonest remaining one expires, so an open feed loses it without a sync. Same rule as iOS.
     */
    private fun publishCards(cards: List<ContentCard>) {
        val now = clock()
        val live = cards.filter { it.expiresAt == null || it.expiresAt > now }
        contentCards.value = live
        scheduleCardRefresh?.invoke(live.mapNotNull { it.expiresAt }.minOrNull()?.let { it - now })
    }

    /** Drops cards that expired since the last publish (the facade calls it at the scheduled time). */
    fun refreshCards() = publishCards(contentCards.value)

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    // endregion

    // region content cards

    /** Logged once per card per session, however many feeds show it (persisted with the session), as on iOS. */
    fun logCardImpression(cardId: String) {
        if (!state.enabled) return
        val card = updateCard(cardId) { it.copy(viewed = true) } ?: return
        ensureSession(clock()) // a timed-out session ends first, clearing the set
        if (cardId in state.impressedCardIds) return
        logInteraction(card.id, "content_card", "impression", card.decisionId)
        state.impressedCardIds.add(cardId)
        state.save()
    }

    fun logCardClick(cardId: String) {
        val card = updateCard(cardId) { it.copy(clicked = true) } ?: return
        logInteraction(card.id, "content_card", "click", card.decisionId)
    }

    fun logCardDismissal(cardId: String) {
        val card = contentCards.value.firstOrNull { it.id == cardId } ?: return
        state.dismissedCardIds.add(cardId)
        state.save()
        publishCards(contentCards.value.filterNot { it.id == cardId })
        logInteraction(card.id, "content_card", "dismiss", card.decisionId)
    }

    private fun updateCard(cardId: String, change: (ContentCard) -> ContentCard): ContentCard? {
        val card = contentCards.value.firstOrNull { it.id == cardId }
            ?: return null.also { HeraldLog.warn("Unknown content card $cardId") }
        publishCards(contentCards.value.map { if (it.id == cardId) change(it) else it })
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

    /**
     * Evaluates a trigger, as on iOS: the winning message joins the pending list and shows as soon as nothing
     * else is on screen, the minimum interval has passed and the app is in the foreground.
     */
    fun trigger(event: TriggerEvent) {
        if (!state.enabled) return
        val message = engine.candidate(event) ?: return
        if (message.id !in state.undeliveredIds) {
            state.undeliveredIds.add(message.id)
            state.save()
        }
        showPendingIfPossible()
    }

    /** Shows the highest-priority pending message now, ignoring the minimum interval (the delegate is still asked). */
    fun showNext(): Boolean = showPendingIfPossible(ignoringInterval = true)

    /**
     * Offers the highest-priority pending message (ties: the oldest) when one may show now; otherwise it stays
     * pending. Called on triggers, foreground, every activity resume, after a message closes, and when the
     * minimum interval runs out. The delegate is asked here, at display time: LATER keeps the message pending
     * for the next of these, DISCARD drops it. Returns true when the message was handed to the presenter.
     */
    fun showPendingIfPossible(ignoringInterval: Boolean = false): Boolean {
        if (!state.enabled || !foreground || engine.onScreen != null) return false
        val pending = state.undeliveredIds.mapNotNull { id -> engine.messages.firstOrNull { it.id == id } }
            .filter { engine.isLive(it) && engine.isEligible(it) }
        if (pending.size != state.undeliveredIds.size) {
            state.undeliveredIds.retainAll(pending.map { it.id }.toSet())
            state.save()
        }
        val message = engine.best(pending) ?: return false
        if (!ignoringInterval && !engine.intervalElapsed()) {
            scheduleShowRetry?.invoke(engine.intervalRemainingMs())
            return false
        }
        val decision = try {
            val ask = { delegate()?.shouldDisplay(message) ?: InAppDisplayDecision.DISPLAY }
            // Blocks the SDK thread while the main thread answers, as the delegate is MainActor on iOS.
            if (mainDispatcher == null) ask() else runBlocking(mainDispatcher) { ask() }
        } catch (e: Exception) {
            HeraldLog.error("InAppMessageDelegate threw; displaying", e)
            InAppDisplayDecision.DISPLAY
        }
        return when (decision) {
            InAppDisplayDecision.DISPLAY -> {
                present(message)
                true
            }
            InAppDisplayDecision.LATER -> false
            InAppDisplayDecision.DISCARD -> {
                HeraldLog.debug("In-app message ${message.id} discarded by delegate")
                state.undeliveredIds.remove(message.id)
                state.save()
                false
            }
        }
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
        showPendingIfPossible()
    }

    val pendingIds: List<String> get() = state.undeliveredIds.toList()

    // endregion

    // region push

    /** [buttonId] is the notification action the user tapped; it logs a `button` interaction after the open. */
    fun onPushOpened(messageId: String, decisionId: String?, buttonId: String? = null) {
        if (!state.enabled) return
        // The system redelivers the original intent extras after recreation; count each decision's open once.
        // Without a decisionId there is no per-send key (a messageId can repeat across sends), so no dedupe.
        if (decisionId != null) {
            if (decisionId in state.recentPushOpens) return
            state.recentPushOpens.add(decisionId)
            while (state.recentPushOpens.size > MAX_RECENT_OPENS) state.recentPushOpens.removeAt(0)
            state.save()
        }
        logInteraction(messageId, "push", "open", decisionId)
        if (buttonId != null) logInteraction(messageId, "push", "button", decisionId, buttonId)
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
        const val MAX_BATCH_BYTES = 512 * 1024
        const val MAX_RECENT_OPENS = 50
        const val SYNC_PUSH_DEBOUNCE_MS = 30_000L
    }
}
