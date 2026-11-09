package com.herald.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.herald.sdk.internal.AndroidDeviceContext
import com.herald.sdk.internal.HeraldCore
import com.herald.sdk.internal.HeraldLog
import com.herald.sdk.internal.HttpUrlConnectionTransport
import com.herald.sdk.internal.PendingCalls
import com.herald.sdk.push.HeraldPushPayload
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * The Herald SDK entry point. Every call is safe from any thread, returns immediately, and never
 * throws: work runs in order on one background thread and errors are logged.
 */
object Herald {
    const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    /** Request code used by [requestPushAuthorization]; match it in `onRequestPermissionsResult`. */
    const val PUSH_PERMISSION_REQUEST_CODE: Int = 0x4E58

    private val _contentCards = MutableStateFlow<List<ContentCard>>(emptyList())

    /** The current user's content cards, unexpired and not dismissed, in server order. */
    val contentCards: StateFlow<List<ContentCard>> = _contentCards.asStateFlow()

    /** Decides whether a triggered in-app message shows now, later or never. */
    @Volatile
    var inAppMessageDelegate: InAppMessageDelegate? = null

    /** Draws in-app messages. herald-ui installs its default; set your own to replace it. */
    @Volatile
    var inAppMessagePresenter: InAppMessagePresenter? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile
    private var scope: CoroutineScope? = null

    /** Read and written only on the SDK thread. */
    private var core: HeraldCore? = null

    /** Calls made before [configure]; replayed in order once it runs. */
    private val pending = PendingCalls<(HeraldCore) -> Unit>()

    /** Main thread only: [requestPushAuthorization] showed the prompt; the result is read on the next resume. */
    private var awaitingPushPermission = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var options = HeraldOptions()
    private var flushTicker: Job? = null
    private var startedActivities = 0

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    /**
     * Starts the SDK. Call once, from `Application.onCreate`. Later calls are ignored. Calls made before it
     * (up to 1,000) are kept and applied, in order, once it runs.
     * [endpoint] is your Herald base URL, for example `https://herald.example.com`.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, appKey: String, endpoint: String, options: HeraldOptions = HeraldOptions()) {
        guard("configure") {
            synchronized(this) {
                if (scope != null) return@guard HeraldLog.warn("Herald.configure called more than once; ignored")
                HeraldLog.level = options.logLevel
                this.options = options
                val app = context.applicationContext
                appContext = app
                val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "herald-sdk").apply { isDaemon = true } }
                    .asCoroutineDispatcher()
                val handler = CoroutineExceptionHandler { _, e -> HeraldLog.error("Herald internal error", e) }
                val newScope = CoroutineScope(SupervisorJob() + dispatcher + handler)
                scope = newScope
                // File reads happen here, off the caller's thread; later calls queue behind this one.
                newScope.launch {
                    val deviceContext = AndroidDeviceContext(app)
                    core = HeraldCore(
                        dir = app.filesDir.resolve("herald"),
                        appKey = appKey,
                        endpoint = endpoint,
                        options = options,
                        transport = HttpUrlConnectionTransport(),
                        contextProvider = deviceContext::snapshot,
                        sdkVersion = SDK_VERSION,
                        bundleId = app.packageName,
                        contentCards = _contentCards,
                    ).also {
                        it.display = ::display
                        it.dismissDisplay = ::dismissDisplay
                        it.delegate = { inAppMessageDelegate }
                    }
                }
                pending.start { block -> newScope.launch { runCall(block) } }
                (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
            }
        }
    }

    // Identity

    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) = submit { it.identify(userId, traits) }

    /**
     * Identity verification token for the identified user (hex HMAC-SHA256 of the user id, from your server).
     * Call it after [identify]: identifying a different user clears the token.
     */
    @JvmStatic
    fun setUserToken(token: String?) = submit { it.setUserToken(token) }

    /**
     * Logs the user out: anonymous again on this device, with a new session. Clears the user id, token,
     * cards and in-app messages and sends the push token again. Device id stays.
     */
    @JvmStatic
    fun reset() = submit { it.reset() }

    // Events

    @JvmStatic
    @JvmOverloads
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) = submit { it.track(event, properties) }

    @JvmStatic
    @JvmOverloads
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) = submit { it.screen(name, properties) }

    @JvmStatic
    @JvmOverloads
    fun trackPurchase(
        productId: String,
        price: Double,
        currency: String,
        quantity: Int = 1,
        properties: Map<String, Any?> = emptyMap(),
    ) = submit { it.trackPurchase(productId, price, currency, quantity, properties) }

    /** Value: String, Number, Boolean, null, or a List of String. Keys up to 255 characters. */
    @JvmStatic
    fun setAttribute(key: String, value: Any?) = submit { it.setAttributes(mapOf(key to value)) }

    @JvmStatic
    fun setAttributes(attributes: Map<String, Any?>) = submit { it.setAttributes(attributes) }

    @JvmStatic
    fun setEmail(email: String?) = submit { it.setAttributes(mapOf("\$email" to email)) }

    @JvmStatic
    fun setPhone(phone: String?) = submit { it.setAttributes(mapOf("\$phone" to phone)) }

    @JvmStatic
    fun setSubscription(channel: SubscriptionChannel, state: SubscriptionState) = submit { it.setSubscription(channel, state) }

    /** Location the app chose to share. The SDK never reads location itself. */
    @JvmStatic
    fun setLocation(latitude: Double, longitude: Double) = submit { it.setLocation(latitude, longitude) }

    // Delivery and privacy

    /** Sends queued events now (subject to retry backoff). */
    @JvmStatic
    fun flush() = submit { it.flush() }

    /** false stops all collection and sending until set back to true. Persisted. */
    @JvmStatic
    fun setEnabled(enabled: Boolean) = submit { it.setEnabled(enabled) }

    /**
     * Deletes every piece of SDK data on the device, including queued events, and disables the SDK.
     * Call `setEnabled(true)` to start again with a new device id.
     */
    @JvmStatic
    fun wipeData() = submit { it.wipeData() }

    // Push

    /**
     * Shows the Android 13+ notification permission prompt when it has not been granted, and records the push
     * subscription: opted_in when allowed (sent once the FCM token exists), unsubscribed when denied (sent at
     * once). Below Android 13, or when already granted, it records whether notifications are enabled.
     * Call from the main thread.
     */
    @JvmStatic
    fun requestPushAuthorization(activity: Activity) {
        guard("requestPushAuthorization") {
            if (Build.VERSION.SDK_INT < 33 ||
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val enabled = manager?.areNotificationsEnabled() ?: true
                return@guard submit { it.recordPushAuthorization(enabled) }
            }
            // The prompt pauses this activity; its answer is read on the next resume (see lifecycle).
            awaitingPushPermission = true
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), PUSH_PERMISSION_REQUEST_CODE)
        }
    }

    /** The FCM registration token. */
    @JvmStatic
    fun setPushToken(token: String) = submit { it.setPushToken(token) }

    /**
     * Call from the activity that receives notification taps (`onCreate` and `onNewIntent`).
     * Logs the open, evaluates `push_open` in-app triggers and opens the deep link.
     * Returns true when the intent came from a Herald notification.
     */
    @JvmStatic
    fun handleNotificationIntent(intent: Intent?): Boolean = try {
        val payload = HeraldPushPayload.fromIntent(intent)
        if (payload == null) {
            false
        } else {
            // Remove the extras so a re-delivered intent (rotation, recreation) is not counted twice.
            intent?.removeExtra(HeraldPushPayload.KEY_DECISION_ID)
            intent?.removeExtra(HeraldPushPayload.KEY_MESSAGE_ID)
            intent?.removeExtra(HeraldPushPayload.KEY_DEEPLINK)
            val actionId = intent?.getStringExtra(HeraldPushPayload.KEY_ACTION_ID)?.takeIf { it.isNotEmpty() }
            intent?.removeExtra(HeraldPushPayload.KEY_ACTION_ID)
            cancelNotification(intent)
            val messageId = payload.messageId ?: payload.decisionId ?: return false
            submit { it.onPushOpened(messageId, payload.decisionId, actionId) }
            payload.deeplink?.let(::openUrl)
            true
        }
    } catch (e: Exception) {
        HeraldLog.error("handleNotificationIntent failed", e)
        false
    }

    /** Action-button taps do not auto-cancel a notification, so clear the one the tap came from. */
    private fun cancelNotification(intent: Intent?) {
        val id = intent?.getIntExtra(HeraldPushPayload.KEY_NOTIFICATION_ID, Int.MIN_VALUE) ?: return
        if (id == Int.MIN_VALUE) return
        intent.removeExtra(HeraldPushPayload.KEY_NOTIFICATION_ID)
        val manager = appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.cancel(HeraldPushPayload.NOTIFICATION_TAG, id)
    }

    // Content cards

    /** Fetches the latest cards and in-app messages now. Sync also happens at session start and on identify. */
    @JvmStatic
    fun refreshContentCards() = submit { it.syncMessages() }

    @JvmStatic
    fun logImpression(card: ContentCard) = submit { it.logCardImpression(card.id) }

    @JvmStatic
    fun logClick(card: ContentCard) = submit { it.logCardClick(card.id) }

    @JvmStatic
    fun logDismissal(card: ContentCard) = submit { it.logCardDismissal(card.id) }

    // In-app messages

    /** Shows the most recent message held with [InAppDisplayDecision.LATER], if no message is on screen. */
    @JvmStatic
    fun showNext() = submit { it.showNext() }

    /**
     * Debug and preview only, not for production use: replaces the current cards and in-app messages in
     * memory with a `GET /api/v1/sdk/messages` response body (for example the shared `messages.json`
     * fixture). Nothing is persisted; the next sync replaces them.
     */
    @JvmStatic
    fun debugLoadMessages(json: String) = submit { core ->
        if (!core.debugLoadMessages(json)) HeraldLog.warn("debugLoadMessages: unreadable body")
    }

    /**
     * Debug and preview only: shows a message loaded with [debugLoadMessages] (or synced) now, skipping its
     * trigger, re-eligibility and the minimum interval. Ignored while a message is on screen.
     */
    @JvmStatic
    fun debugShowInAppMessage(messageId: String) = submit { it.debugShowInAppMessage(messageId) }

    /** Opens a deep link or URL with ACTION_VIEW. Presenters use it for button and click actions. */
    @JvmStatic
    fun openUrl(url: String) {
        guard("openUrl") {
            val context = appContext ?: return@guard
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // Internals

    private fun submit(block: (HeraldCore) -> Unit) {
        if (pending.offer(block)) return
        scope?.launch { runCall(block) }
    }

    private fun runCall(block: (HeraldCore) -> Unit) {
        try {
            core?.let(block)
        } catch (e: Exception) {
            HeraldLog.error("Herald call failed", e)
        }
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            HeraldLog.error("$name failed", e)
        }
    }

    /** Main thread only: the activity and message currently presented, to free the screen if the activity dies. */
    private var presented: Pair<WeakReference<Activity>, InAppMessage>? = null

    /** Called on the SDK thread. Returns false when no presenter or activity can take the message now. */
    private fun display(message: InAppMessage): Boolean {
        val presenter = inAppMessagePresenter ?: return false
        if (currentActivity?.get() == null) return false
        mainHandler.post {
            val activity = currentActivity?.get()
            val shown = try {
                activity != null && presenter.present(activity, message, Callbacks(message))
            } catch (e: Exception) {
                HeraldLog.error("InAppMessagePresenter threw", e)
                false
            }
            if (shown) {
                presented = WeakReference<Activity>(activity!!) to message
                submit { it.presentAccepted(message) }
            } else {
                submit { it.presentFailed(message) }
            }
        }
        return true
    }

    private fun dismissDisplay() {
        val presenter = inAppMessagePresenter ?: return
        mainHandler.post {
            presented = null
            guard("dismissCurrent") { presenter.dismissCurrent() }
        }
    }

    private class Callbacks(private val message: InAppMessage) : InAppMessageCallbacks {
        override fun onImpression() = submit { it.onInAppImpression(message) }
        override fun onClick(buttonId: String?) = submit { it.onInAppClick(message, buttonId) }
        override fun onDismiss() {
            mainHandler.post { if (presented?.second === message) presented = null }
            submit { it.onInAppDismiss(message) }
        }
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            if (startedActivities == 1) {
                submit { it.onForeground() }
                flushTicker = scope?.launch {
                    val intervalMs = options.flushIntervalSeconds.coerceAtLeast(1) * 1000
                    while (isActive) {
                        delay(intervalMs)
                        try {
                            core?.flush()
                        } catch (e: Exception) {
                            HeraldLog.error("Periodic flush failed", e)
                        }
                    }
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) {
                flushTicker?.cancel()
                flushTicker = null
                submit { it.onBackground() }
            }
        }

        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
            if (awaitingPushPermission && Build.VERSION.SDK_INT >= 33) {
                awaitingPushPermission = false
                val granted = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                submit { it.recordPushAuthorization(granted) }
            }
            submit { it.retryUndelivered() }
        }

        override fun onActivityPaused(activity: Activity) {
            if (currentActivity?.get() === activity) currentActivity = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            val (ref, message) = presented ?: return
            if (ref.get() === activity || ref.get() == null) {
                presented = null
                submit { it.presenterGone(message) }
            }
        }
    }
}
