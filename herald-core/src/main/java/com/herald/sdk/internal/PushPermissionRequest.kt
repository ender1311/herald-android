package com.herald.sdk.internal

/**
 * Tracks an open notification permission prompt. The answer is read on the first resume that follows a
 * pause after the request: an activity's own first resume (request made in `onCreate`/`onStart`) happens
 * while the prompt is still up and must not count. Main thread only.
 */
internal class PushPermissionRequest {
    private var awaiting = false
    private var pausedSinceRequest = false

    fun requested() {
        awaiting = true
        pausedSinceRequest = false
    }

    fun onPaused() {
        if (awaiting) pausedSinceRequest = true
    }

    /** True when the prompt has closed and its answer should be read now. */
    fun onResumed(): Boolean {
        if (!awaiting || !pausedSinceRequest) return false
        awaiting = false
        pausedSinceRequest = false
        return true
    }
}
