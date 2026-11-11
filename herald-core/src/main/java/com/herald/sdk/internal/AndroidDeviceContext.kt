package com.herald.sdk.internal

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** Reads the device context from platform APIs. Never collects advertising IDs, location or carrier. */
internal class AndroidDeviceContext(context: Context) {
    private val app = context.applicationContext

    private val packageInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()

    fun snapshot(): DeviceContext {
        val metrics = app.resources.displayMetrics
        val config = app.resources.configuration
        val locale = Locale.getDefault()
        return DeviceContext(
            appName = runCatching { app.applicationInfo.loadLabel(app.packageManager).toString() }.getOrDefault(app.packageName),
            appVersion = packageInfo?.versionName ?: "unknown",
            appBuild = packageInfo?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString() } ?: "0",
            appNamespace = app.packageName,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            region = locale.country.takeIf { it.isNotEmpty() },
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            deviceType = if (config.smallestScreenWidthDp >= 600) "tablet" else "phone",
            screenWidth = (metrics.widthPixels / metrics.density).toInt(),
            screenHeight = (metrics.heightPixels / metrics.density).toInt(),
            screenDensity = metrics.density.toDouble(),
            locale = locale.toLanguageTag(),
            language = locale.language.takeIf { it.isNotEmpty() },
            timezone = TimeZone.getDefault().id,
            networkType = networkType(),
            pushAuthorization = pushAuthorization(),
        )
    }

    private fun networkType(): String? = runCatching {
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }.getOrNull()

    private fun pushAuthorization(): String? = runCatching {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return null
        val sdk = Build.VERSION.SDK_INT
        val granted = sdk < 33 || app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        pushAuthorizationValue(sdk, granted, nm.areNotificationsEnabled(), pushPermissionMarker(app).exists())
    }.getOrNull()

    companion object {
        /** Written when `Herald.requestPushAuthorization` shows the Android 13+ prompt. Kept by `wipeData()`. */
        fun pushPermissionMarker(context: Context) =
            File(context.filesDir.resolve(StateStore.STORAGE_DIR), "push_permission_requested")

        /**
         * iOS value names. Android 13+: `not_determined` until the permission has been requested, then
         * `authorized` or `denied`. Before 13 the permission is implicit: authorized unless notifications are off.
         */
        fun pushAuthorizationValue(sdkInt: Int, permissionGranted: Boolean, notificationsEnabled: Boolean, requested: Boolean) = when {
            sdkInt >= 33 && !permissionGranted -> if (requested) "denied" else "not_determined"
            notificationsEnabled -> "authorized"
            else -> "denied"
        }
    }
}
