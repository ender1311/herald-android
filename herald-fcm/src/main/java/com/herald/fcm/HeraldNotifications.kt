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
import androidx.annotation.DrawableRes
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
    /** Whole download, so a slow-drip server cannot hold onMessageReceived past FCM's window. */
    private const val IMAGE_DEADLINE_MS = 8_000L
    private const val IMAGE_MAX_BYTES = 5 * 1024 * 1024
    private const val IMAGE_MAX_WIDTH_PX = 1024
    private const val IMAGE_MAX_HEIGHT_PX = 1024
    private const val IMAGE_MAX_PIXELS = 1024 * 1024

    /**
     * Small icon for Herald notifications: a monochrome drawable resource (white on transparent).
     * 0 (default) uses the `com.google.firebase.messaging.default_notification_icon` meta-data, then
     * herald-fcm's own bell icon. The app's launcher icon is never used: an adaptive icon as the small
     * icon crashes the app on Android 8.0.
     */
    @JvmStatic
    @DrawableRes
    var smallIcon: Int = 0

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
            val intent = openIntent(context, actionPayload, notificationId, index + 1, action.id) ?: return@forEachIndexed
            builder.addAction(Notification.Action.Builder(null as Icon?, action.title, intent).build())
        }
        manager.notify(TAG, notificationId, builder.build())
    }

    /** Tag for Herald notifications; `Herald.handleNotificationIntent` cancels by tag and id. */
    const val TAG = HeraldPushPayload.NOTIFICATION_TAG

    private fun openIntent(
        context: Context,
        payload: HeraldPushPayload,
        notificationId: Int,
        slot: Int,
        actionId: String? = null,
    ): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        payload.putInto(launch).putExtra(HeraldPushPayload.KEY_NOTIFICATION_ID, notificationId)
        actionId?.let { launch.putExtra(HeraldPushPayload.KEY_ACTION_ID, it) }
        return PendingIntent.getActivity(
            context,
            notificationId * 4 + slot,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun smallIcon(context: Context): Int {
        if (smallIcon != 0) return smallIcon
        val meta = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        // Same meta-data key Firebase uses for its own notifications.
        return meta?.getInt("com.google.firebase.messaging.default_notification_icon", 0)?.takeIf { it != 0 }
            ?: R.drawable.herald_ic_notification
    }

    /** Smallest power of two that fits the decoded image in 1024 x 1024 px. */
    internal fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (
            width / sample > IMAGE_MAX_WIDTH_PX ||
            height / sample > IMAGE_MAX_HEIGHT_PX ||
            (width / sample).toLong() * (height / sample) > IMAGE_MAX_PIXELS
        ) sample *= 2
        return sample
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
            val deadline = System.currentTimeMillis() + IMAGE_DEADLINE_MS
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > IMAGE_MAX_BYTES || System.currentTimeMillis() > deadline) return null
                }
            }
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = sampleSize(bounds.outWidth, bounds.outHeight)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            Log.w("Herald", "Notification image failed (${e.javaClass.simpleName})")
            null
        } catch (e: OutOfMemoryError) {
            Log.w("Herald", "Notification image too large to decode")
            null
        } finally {
            connection.disconnect()
        }
    }
}
