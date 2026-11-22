package com.herald.sdk

/** How much device context the SDK sends. MINIMAL omits every tier-2 field. */
enum class DeviceDataCollection { STANDARD, MINIMAL }

enum class LogLevel { DEBUG, INFO, WARN, ERROR, NONE }

/** Options passed to [Herald.configure]. Defaults match the documented delivery rules. */
data class HeraldOptions(
    val deviceDataCollection: DeviceDataCollection = DeviceDataCollection.STANDARD,
    val flushIntervalSeconds: Long = 10,
    val flushAt: Int = 20,
    val sessionTimeoutMinutes: Long = 30,
    val inAppMinimumIntervalSeconds: Long = 30,
    val logLevel: LogLevel = LogLevel.WARN,
    /** Records a push your app sent itself (not from Herald) as `$external_push` when herald-fcm receives it, so Herald's push limit counts it. */
    val trackExternalPushes: Boolean = true,
)

/** The kind of push your app sent itself, reported with [Herald.trackExternalPush]. */
enum class ExternalPushCategory(internal val wire: String) { TRANSACTIONAL("transactional"), MARKETING("marketing"), OTHER("other") }

enum class SubscriptionChannel(internal val wire: String) { PUSH("push"), EMAIL("email"), SMS("sms") }

enum class SubscriptionState(internal val wire: String) {
    SUBSCRIBED("subscribed"),
    UNSUBSCRIBED("unsubscribed"),
    OPTED_IN("opted_in"),
}
