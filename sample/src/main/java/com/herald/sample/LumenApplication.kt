package com.herald.sample

import android.app.Application
import com.herald.fcm.HeraldMessagingService
import com.herald.fcm.HeraldNotifications
import com.herald.sdk.Herald
import com.herald.sdk.HeraldOptions
import com.herald.sdk.LogLevel
import com.herald.ui.HeraldInAppPresenter

class LumenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Herald.configure(
            this,
            appKey = BuildConfig.HERALD_APP_KEY,
            endpoint = BuildConfig.HERALD_ENDPOINT,
            options = HeraldOptions(
                logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN,
                sessionTimeoutMinutes = BuildConfig.HERALD_SESSION_TIMEOUT_MINUTES,
            ),
        )
        HeraldInAppPresenter.install()
        HeraldNotifications.createDefaultChannel(this)
        // No-op without google-services.json (Firebase not initialized).
        HeraldMessagingService.syncToken(this)
    }
}
