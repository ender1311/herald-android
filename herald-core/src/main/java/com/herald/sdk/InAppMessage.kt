package com.herald.sdk

import android.app.Activity

enum class InAppMessageType(internal val wire: String) {
    SLIDEUP("slideup"),
    MODAL("modal"),
    FULL("full"),
    HTML("html"),
}

enum class TriggerType(internal val wire: String) {
    SESSION_START("session_start"),
    EVENT("event"),
    PUSH_OPEN("push_open"),
}

enum class FilterOperator(internal val wire: String) {
    EQ("eq"), NEQ("neq"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), CONTAINS("contains"), EXISTS("exists"),
}

data class PropertyFilter(val key: String, val op: FilterOperator, val value: Any?)

data class InAppTrigger(
    val type: TriggerType,
    val event: String?,
    val propertyFilters: List<PropertyFilter>,
)

enum class ReEligibilityMode(internal val wire: String) { ONCE("once"), AFTER_HOURS("after_hours") }

data class ReEligibility(val mode: ReEligibilityMode, val hours: Double?)

enum class ButtonActionType(internal val wire: String) { DEEPLINK("deeplink"), URL("url"), CLOSE("close") }

data class ButtonAction(val type: ButtonActionType, val value: String?)

data class InAppButton(val id: String, val text: String, val action: ButtonAction)

data class InAppContent(
    val header: String?,
    val body: String?,
    val imageUrl: String?,
    val buttons: List<InAppButton>,
    val html: String?,
    /** "top" or "bottom" for slideups; null means the presenter's default. */
    val slideFrom: String?,
    val dismissAfterSeconds: Double?,
)

/** Null fields mean "use the app theme". Colors are hex strings such as "#101828". */
data class InAppStyle(
    val backgroundColor: String?,
    val textColor: String?,
    val buttonColor: String?,
    val buttonTextColor: String?,
    val cornerRadius: Double?,
)

/** An in-app message synced from Herald and evaluated on the device. */
data class InAppMessage(
    val id: String,
    val type: InAppMessageType,
    val priority: Int,
    val trigger: InAppTrigger,
    val reEligibility: ReEligibility,
    val expiresAt: Long?,
    val content: InAppContent,
    val style: InAppStyle,
    val decisionId: String?,
)

enum class InAppDisplayDecision { DISPLAY, LATER, DISCARD }

/**
 * Lets the app show, hold or drop a triggered message. Asked at display time, on the SDK's background
 * thread; return quickly. LATER keeps the message pending: it is offered again (and the delegate asked
 * again) on the next trigger, foreground, activity resume or message close, or by [Herald.showNext].
 */
fun interface InAppMessageDelegate {
    fun shouldDisplay(message: InAppMessage): InAppDisplayDecision
}

/** Callbacks a presenter reports back through so Herald logs interactions and frees the screen. */
interface InAppMessageCallbacks {
    /** The message became visible. */
    fun onImpression()

    /** The user tapped the message body (buttonId null) or a button. */
    fun onClick(buttonId: String?)

    /** The message left the screen, for any reason. Must be called exactly once. */
    fun onDismiss()
}

/**
 * Draws in-app messages. herald-ui supplies the default; apps may provide their own.
 * Called on the main thread. Return true only when the message is now on screen; Herald then marks it
 * shown. Return false and Herald retries it on the next activity resume.
 */
fun interface InAppMessagePresenter {
    fun present(activity: Activity, message: InAppMessage, callbacks: InAppMessageCallbacks): Boolean

    /** Take down the message on screen without user action (the user was reset or switched). Main thread. */
    fun dismissCurrent() {}
}
