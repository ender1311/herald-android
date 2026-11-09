package com.herald.sdk.internal

import android.util.Log
import com.herald.sdk.LogLevel

internal object HeraldLog {
    private const val TAG = "Herald"

    @Volatile
    var level: LogLevel = LogLevel.WARN

    fun debug(message: String) {
        if (level <= LogLevel.DEBUG) Log.d(TAG, message)
    }

    fun info(message: String) {
        if (level <= LogLevel.INFO) Log.i(TAG, message)
    }

    fun warn(message: String, error: Throwable? = null) {
        if (level <= LogLevel.WARN) Log.w(TAG, message, error)
    }

    fun error(message: String, error: Throwable? = null) {
        if (level <= LogLevel.ERROR) Log.e(TAG, message, error)
    }
}
