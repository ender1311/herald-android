package com.herald.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small image loader for card and message images: HttpURLConnection, an in-memory LRU cache sized
 * in bytes, and downsampling to the size the view needs. Also reads content://, file:// and
 * android.resource:// URIs, so apps can ship images in their own resources.
 */
internal object HeraldImages {
    private const val MAX_BYTES = 10 * 1024 * 1024
    private const val TIMEOUT_MS = 10_000

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(32L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String, maxWidthPx: Int): Bitmap? = cache.get(key(url, maxWidthPx))

    /** Loads on the IO dispatcher. Returns null (and logs) on any failure. */
    suspend fun load(context: Context, url: String, maxWidthPx: Int): Bitmap? {
        cached(url, maxWidthPx)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = read(context, url) ?: return@withContext null
                decode(bytes, maxWidthPx)?.also { cache.put(key(url, maxWidthPx), it) }
            } catch (e: Exception) {
                Log.w("Herald", "Image load failed for $url (${e.javaClass.simpleName})")
                null
            }
        }
    }

    private fun key(url: String, maxWidthPx: Int) = "$maxWidthPx|$url"

    private fun read(context: Context, url: String): ByteArray? {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "https", "http" -> {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    if (connection.responseCode !in 200..299) return null
                    connection.inputStream.use(::readCapped)
                } finally {
                    connection.disconnect()
                }
            }
            "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)?.use(::readCapped)
            else -> null
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, maxWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, maxWidthPx) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Largest power of two that keeps the decoded width at or above [maxWidthPx]. */
    fun sampleSize(width: Int, maxWidthPx: Int): Int {
        var sample = 1
        while (maxWidthPx > 0 && width / (sample * 2) >= maxWidthPx) sample *= 2
        return sample
    }
}

/** The image at [url], or null while loading and on failure. */
@Composable
internal fun rememberHeraldImage(url: String?, maxWidthPx: Int): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(url, maxWidthPx) {
        mutableStateOf(url?.let { HeraldImages.cached(it, maxWidthPx)?.asImageBitmap() })
    }
    LaunchedEffect(url, maxWidthPx) {
        if (url != null && image == null) image = HeraldImages.load(context, url, maxWidthPx)?.asImageBitmap()
    }
    return image
}
