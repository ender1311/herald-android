package com.herald.sdk.internal

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

internal data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    /** Case-insensitive header lookup. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Network seam; tests inject a fake. Throws IOException on network failure. */
internal fun interface Transport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

internal class HttpUrlConnectionTransport : Transport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { it.value.joinToString(",") }
            return HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }
}
