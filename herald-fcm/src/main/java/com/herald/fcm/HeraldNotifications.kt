package com.herald.fcm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Log
import com.google.firebase.messaging.RemoteMessage
import com.herald.sdk.push.HeraldPushPayload
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One action button on a Herald notification. */
data class HeraldNotificationAction(val id: String, val title: String, val deeplink: String?)

/**
 * Shows Herald pushes as system notifications. Taps open the app's launch activity with the Herald
 * extras, where `Herald.handleNotificationIntent(intent)` logs the open and opens the deep link.
 */
object HeraldNotifications {
    const val DEFAULT_CHANNEL_ID = "herald_default"

    /** Data key with up to 3 action buttons: a JSON array of `{"id", "title", "deeplink"?}`. */
    const val KEY_ACTIONS = "herald_actions"

    /** Data key for the notification channel id; the FCM `android.notification.channel_id` also works. */
    const val KEY_CHANNEL_ID = "herald_channel_id"

    private const val MAX_ACTIONS = 3
    private const val IMAGE_TIMEOUT_MS = 5_000
    private const val IMAGE_MAX_BYTES = 5 * 1024 * 1024
    private const val IMAGE_MAX_WIDTH_PX = 1024

    /**
     * Creates Herald's default channel (id [DEFAULT_CHANNEL_ID]) if it does not exist. The name comes from
     * the `herald_default_channel_name` string resource unless [name] is given.
     */
    @JvmStatic
    @JvmOverloads
    fun createDefaultChannel(
        context: Context,
        name: CharSequence = context.getString(R.string.herald_default_channel_name),
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DEFAULT_CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(DEFAULT_CHANNEL_ID, name, importance))
    }

    /**
     * Shows [message] when it is a Herald push. Returns false for other pushes, so a custom
     * FirebaseMessagingService can pass them on. Blocks while the image downloads (up to a few seconds);
     * FCM calls `onMessageReceived` off the main thread.
     */
    @JvmStatic
    fun handleRemoteMessage(context: Context, message: RemoteMessage): Boolean {
        val payload = payloadOf(message.data, message.notification) ?: return false
        try {
            val channel = message.data[KEY_CHANNEL_ID] ?: message.notification?.channelId
            show(context, payload, parseActions(message.data[KEY_ACTIONS]), channel)
        } catch (e: Exception) {
            Log.w("Herald", "Could not show Herald notification", e)
        }
        return true
    }

    /** The Herald fields of a push, with title, body and image falling back to the FCM notification block. */
    internal fun payloadOf(data: Map<String, String>, notification: RemoteMessage.Notification?): HeraldPushPayload? {
        val payload = HeraldPushPayload.fromData(data) ?: return null
        return payload.copy(
            title = payload.title ?: notification?.title,
            body = payload.body ?: notification?.body,
            imageUrl = payload.imageUrl ?: notification?.imageUrl?.toString(),
        )
    }

    /** Parses [KEY_ACTIONS]; skips malformed entries and keeps at most 3. */
    internal fun parseActions(json: String?): List<HeraldNotificationAction> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val deeplink = if (item.isNull("deeplink")) null else item.optString("deeplink").takeIf { it.isNotBlank() }
            HeraldNotificationAction(id, title, deeplink)
        }.take(MAX_ACTIONS)
    }

    private fun show(context: Context, payload: HeraldPushPayload, actions: List<HeraldNotificationAction>, channelId: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val channel = channelId?.takeIf { manager.getNotificationChannel(it) != null }
            ?: DEFAULT_CHANNEL_ID.also { createDefaultChannel(context) }
        val notificationId = (payload.decisionId ?: payload.messageId ?: "").hashCode()

        val builder = Notification.Builder(context, channel)
            .setSmallIcon(smallIcon(context))
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, payload, notificationId, 0) ?: return)
        val image = payload.imageUrl?.let(::downloadImage)
        if (image != null) {
            builder.setLargeIcon(image)
                .setStyle(Notification.BigPictureStyle().bigPicture(image).bigLargeIcon(null as Icon?).setSummaryText(payload.body))
        } else if (payload.body != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(payload.body))
        }
        actions.forEachIndexed { index, action ->
            val actionPayload = payload.copy(deeplink = action.deeplink ?: payload.deeplink)
            val intent = openIntent(context, actionPayload, notificationId, index + 1) ?: return@forEachIndexed
            builder.addAction(Notification.Action.Builder(null as Icon?, action.title, intent).build())
        }
        manager.notify(TAG, notificationId, builder.build())
    }

    /** Tag for Herald notifications; `Herald.handleNotificationIntent` cancels by tag and id. */
    const val TAG = HeraldPushPayload.NOTIFICATION_TAG

    private fun openIntent(context: Context, payload: HeraldPushPayload, notificationId: Int, slot: Int): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        payload.putInto(launch).putExtra(HeraldPushPayload.KEY_NOTIFICATION_ID, notificationId)
        return PendingIntent.getActivity(
            context,
            notificationId * 4 + slot,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun smallIcon(context: Context): Int {
        val meta = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        // Same meta-data key Firebase uses for its own notifications.
        return meta?.getInt("com.google.firebase.messaging.default_notification_icon", 0)?.takeIf { it != 0 }
            ?: context.applicationInfo.icon
    }

    private fun downloadImage(url: String): Bitmap? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = IMAGE_TIMEOUT_MS
            connection.readTimeout = IMAGE_TIMEOUT_MS
            if (connection.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > IMAGE_MAX_BYTES) return null
                }
            }
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= IMAGE_MAX_WIDTH_PX) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            Log.w("Herald", "Notification image failed (${e.javaClass.simpleName})")
            null
        } finally {
            connection.disconnect()
        }
    }
}
