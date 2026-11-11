package com.herald.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.herald.sdk.Herald

/**
 * Registered by herald-fcm's manifest. Sends new FCM tokens to Herald, shows Herald pushes that
 * reach the app (data messages, and notification messages while the app is in the foreground) and
 * refreshes cards and in-app messages on a Herald sync push (data-only, never shown).
 * Subclass it and override [onNonHeraldMessage] to handle your other pushes.
 */
open class HeraldMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Herald.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!HeraldNotifications.handleRemoteMessage(this, message)) onNonHeraldMessage(message)
    }

    /** A push that did not come from Herald. Does nothing by default. */
    open fun onNonHeraldMessage(message: RemoteMessage) {}

    companion object {
        /**
         * Fetches the current FCM token and passes it to [Herald.setPushToken]. Call after `Herald.configure`
         * so a token issued before Herald was added is registered too. Does nothing when Firebase is not
         * initialized (no google-services.json).
         */
        @JvmStatic
        fun syncToken(context: Context) {
            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    Log.w("Herald", "Firebase is not initialized; push token not synced")
                    return
                }
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> Herald.setPushToken(token) }
            } catch (e: Exception) {
                Log.w("Herald", "FCM token sync failed", e)
            }
        }
    }
}
