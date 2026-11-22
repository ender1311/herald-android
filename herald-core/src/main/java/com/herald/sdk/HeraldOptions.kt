package com.herald.sdk

/** How much device context the SDK sends. MINIMAL omits every tier-2 field. */
enum class DeviceDataCollection { STANDARD, MINIMAL }

enum class LogLevel { DEBUG, INFO, WARN, ERROR, NONE }

/**
 * Options passed to [Herald.configure]. Defaults match the documented delivery rules.
 *
 * [sessionTimeoutMinutes] is deprecated: it applies to [foregroundSessionTimeoutMinutes] and
 * [backgroundSessionTimeoutMinutes] when they are null (the default), so setting it alone (or in `copy`) sets both.
 */
data class HeraldOptions(
    val deviceDataCollection: DeviceDataCollection = DeviceDataCollection.STANDARD,
    val flushIntervalSeconds: Long = 10,
    val flushAt: Int = 20,
    @Deprecated("Use foregroundSessionTimeoutMinutes and backgroundSessionTimeoutMinutes")
    val sessionTimeoutMinutes: Long = 30,
    val inAppMinimumIntervalSeconds: Long = 30,
    val logLevel: LogLevel = LogLevel.WARN,
    /** Records a push your app sent itself (not from Herald) as `$external_push` when herald-fcm receives it, so Herald's push limit counts it. */
    val trackExternalPushes: Boolean = true,
    /** Inactivity in the foreground that ends a session; a cold start within it of the last activity continues it. Null: [sessionTimeoutMinutes]. */
    val foregroundSessionTimeoutMinutes: Long? = null,
    /** Time in the background that ends a session. Null: [sessionTimeoutMinutes]. */
    val backgroundSessionTimeoutMinutes: Long? = null,
    /** Sends `$app_installed`, `$app_updated`, `$app_opened`, `$app_foreground` and `$app_background`. */
    val trackLifecycleEvents: Boolean = true,
    /** Sends `$deep_link_opened` when a deep link goes through the SDK (push taps and [Herald.openUrl]). */
    val trackDeepLinks: Boolean = true,
    /** Adds the deep link's query string to `$deep_link_opened` (off: it can hold personal data). */
    val trackDeepLinkQuery: Boolean = false,
    /** Queued events older than this are dropped before a batch is built and counted in `dropped` (Herald rejects SDK events over 30 days old). */
    val maxQueuedEventAgeDays: Long = 30,
) {
    // Resolved at use, so `copy(sessionTimeoutMinutes = …)` sets both too.
    @Suppress("DEPRECATION")
    internal val foregroundTimeoutMinutes: Long get() = foregroundSessionTimeoutMinutes ?: sessionTimeoutMinutes

    @Suppress("DEPRECATION")
    internal val backgroundTimeoutMinutes: Long get() = backgroundSessionTimeoutMinutes ?: sessionTimeoutMinutes
}

/** The kind of push your app sent itself, reported with [Herald.trackExternalPush]. */
enum class ExternalPushCategory(internal val wire: String) { TRANSACTIONAL("transactional"), MARKETING("marketing"), OTHER("other") }

enum class SubscriptionChannel(internal val wire: String) { PUSH("push"), EMAIL("email"), SMS("sms") }

enum class SubscriptionState(internal val wire: String) {
    SUBSCRIBED("subscribed"),
    UNSUBSCRIBED("unsubscribed"),
    OPTED_IN("opted_in"),
}
