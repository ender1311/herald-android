package com.herald.sdk.internal

/**
 * Client-side copies of the server's batch schema limits (herald `src/lib/sdk/schema.ts`).
 * The server rejects a whole batch for one bad event, so a bad call is refused before it is queued.
 * Each check returns a problem description, or null when the value is valid.
 */
internal object Validation {
    const val MAX_NAME = 255
    const val MAX_USER_ID = 200
    const val MAX_MESSAGE_ID = 200
    const val MAX_PUSH_TOKEN = 4096

    fun length(field: String, value: String?, min: Int, max: Int): String? = when {
        value == null -> "$field is required"
        value.length < min -> "$field must not be empty"
        value.length > max -> "$field is longer than $max characters"
        else -> null
    }

    /** Attribute and trait maps: keys 1-255 chars; values string, finite number, boolean, null or list of strings. */
    fun attributes(field: String, map: Map<String, Any?>): String? {
        for ((key, value) in map) {
            length("$field key", key, 1, MAX_NAME)?.let { return it }
            if (!isAttributeValue(value)) return "$field '$key' must be a string, number, boolean, null or list of strings"
        }
        return null
    }

    /** Event properties: keys up to 255 chars; any JSON value (checked when converting). */
    fun properties(field: String, map: Map<String, Any?>): String? =
        map.keys.firstOrNull { it.length > MAX_NAME }?.let { "$field key is longer than $MAX_NAME characters" }

    private fun isAttributeValue(value: Any?): Boolean = when (value) {
        null, is String, is Boolean -> true
        is Number -> value.toDouble().isFinite()
        is Collection<*> -> value.all { it is String }
        is Array<*> -> value.all { it is String }
        else -> false
    }
}
