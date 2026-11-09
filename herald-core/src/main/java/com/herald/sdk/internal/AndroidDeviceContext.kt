package com.herald.sdk.internal

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
        if (nm.areNotificationsEnabled()) "authorized" else "denied"
    }.getOrNull()
}
