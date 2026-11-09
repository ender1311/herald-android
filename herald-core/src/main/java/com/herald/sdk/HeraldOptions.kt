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
)

enum class SubscriptionChannel(internal val wire: String) { PUSH("push"), EMAIL("email"), SMS("sms") }

enum class SubscriptionState(internal val wire: String) {
    SUBSCRIBED("subscribed"),
    UNSUBSCRIBED("unsubscribed"),
    OPTED_IN("opted_in"),
}
