package com.herald.sdk.push

/**
 * For herald-fcm, not for apps: fetches the current push token and passes it to `Herald.setPushToken`.
 * `HeraldMessagingService.syncToken` sets it. The SDK calls it when it is turned back on with notifications
 * allowed and no stored token (after `wipeData()`), since FCM does not issue the unchanged token again.
 */
object PushTokenSource {
    @Volatile
    var request: (() -> Unit)? = null
}
