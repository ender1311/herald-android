package com.herald.sdk.internal

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A random per-install secret that herald-fcm puts on its notification tap intents. The launcher activity is
 * exported, so any app can send it an intent with Herald's extras; only an intent carrying this token (which
 * other apps cannot read) is treated as a Herald notification tap.
 */
internal object TapToken {
    const val FILE_NAME = "tap_token"
    private val lock = Any()
    @Volatile private var cached: Pair<File, String>? = null

    /** The token stored in [dir], created on first use. */
    fun get(dir: File): String = synchronized(lock) {
        cached?.takeIf { it.first == dir }?.let { return it.second }
        val file = File(dir, FILE_NAME)
        val existing = file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length == 32 }
        val token = existing ?: newToken().also { token ->
            dir.mkdirs()
            val temp = File(dir, "$FILE_NAME.tmp")
            temp.writeText(token)
            if (!temp.renameTo(file)) file.writeText(token)
        }
        cached = dir to token
        token
    }

    /** True only when [presented] equals the stored token (constant-time compare). */
    fun matches(dir: File, presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(get(dir).toByteArray(), presented.toByteArray())
    }

    private fun newToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
