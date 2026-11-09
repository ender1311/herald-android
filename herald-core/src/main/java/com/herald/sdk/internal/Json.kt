package com.herald.sdk.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_MILLIS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

internal fun isoTimestamp(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

/** Parses an ISO-8601 timestamp with an offset or `Z`; null when absent or malformed. */
internal fun parseIso(value: String?): Long? =
    value?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Converts a Kotlin value to its org.json form; throws IllegalArgumentException on unsupported values. */
internal fun toJsonValue(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is String, is Boolean, is Int, is Long -> value
    is Number -> {
        val d = value.toDouble()
        require(d.isFinite()) { "non-finite number" }
        if (value is Float) d else value
    }
    is Map<*, *> -> JSONObject().also { obj ->
        value.forEach { (k, v) -> obj.put(k as? String ?: throw IllegalArgumentException("non-string key"), toJsonValue(v)) }
    }
    is Iterable<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJsonValue(it)) } }
    is Array<*> -> toJsonValue(value.asList())
    is JSONObject, is JSONArray -> value
    else -> throw IllegalArgumentException("unsupported type ${value::class.java.simpleName}")
}

/** Converts org.json values back to plain Kotlin (Map, List, String, Number, Boolean, null). */
internal fun fromJsonValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> (0 until value.length()).map { fromJsonValue(value.opt(it)) }
    else -> value
}

internal fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { fromJsonValue(opt(it)) }

// Android's optString returns "null" for JSON null, so read typed values explicitly.
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else opt(key) as? String
internal fun JSONObject.num(key: String): Number? = if (isNull(key)) null else opt(key) as? Number
internal fun JSONObject.bool(key: String): Boolean? = if (isNull(key)) null else opt(key) as? Boolean
internal fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else opt(key) as? JSONObject
internal fun JSONObject.arr(key: String): JSONArray? = if (isNull(key)) null else opt(key) as? JSONArray

/** Writes via a temp file and rename so a crash mid-write never leaves a truncated file. */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        delete()
        check(tmp.renameTo(this)) { "rename failed for $name" }
    }
}
