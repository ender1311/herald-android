package com.herald.sdk.internal

/**
 * App-level foreground and background from activity starts and stops. A configuration change (rotation, dark mode,
 * a resize) stops the old activity before the new one starts; that is not a transition.
 */
internal class ForegroundTracker {
    private var started = 0
    private var changingConfiguration = false

    /** An activity started; true when the app moved to the foreground. */
    fun onStarted(): Boolean {
        started++
        if (changingConfiguration) {
            changingConfiguration = false
            return false
        }
        return started == 1
    }

    /** An activity stopped; true when the app moved to the background. */
    fun onStopped(isChangingConfigurations: Boolean): Boolean {
        started = (started - 1).coerceAtLeast(0)
        if (isChangingConfigurations) {
            changingConfiguration = true
            return false
        }
        return started == 0
    }
}
