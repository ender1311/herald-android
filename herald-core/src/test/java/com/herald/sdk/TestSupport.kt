package com.herald.sdk

import com.herald.sdk.internal.DeviceContext
import com.herald.sdk.internal.HeraldCore
import com.herald.sdk.internal.HttpRequest
import com.herald.sdk.internal.HttpResponse
import com.herald.sdk.internal.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

/** 2026-11-09T14:00:00.000Z, the fixtures' timestamp. */
const val FIXTURE_NOW = 1_794_232_800_000L

internal val fixtureContext = DeviceContext(
    appName = "Lumen", appVersion = "4.2.0", appBuild = "812", appNamespace = "com.lumen.app",
    osName = "iOS", osVersion = "18.1", region = "US",
    manufacturer = "Apple", model = "iPhone16,1", deviceType = "phone",
    screenWidth = 393, screenHeight = 852, screenDensity = 3,
    locale = "en-US", language = "en", timezone = "America/Chicago",
    networkType = "wifi", pushAuthorization = "authorized",
)

internal class FakeTransport : Transport {
    val requests = mutableListOf<HttpRequest>()
    var batchResponse: () -> HttpResponse = { HttpResponse(200, emptyMap(), """{"data":{"accepted":1,"duplicates":0}}""") }
    /** Like the server: an anonymous request (no userId) gets empty lists. */
    var messagesResponse: (HttpRequest) -> HttpResponse = { request ->
        if ("userId=" in request.url) HttpResponse(200, mapOf("ETag" to "\"v1\""), fixture("messages.json"))
        else HttpResponse(200, emptyMap(), fixture("messages-empty.json"))
    }

    val batches: List<JSONObject> get() = requests.filter { it.method == "POST" }.map { JSONObject(it.body!!) }
    val gets: List<HttpRequest> get() = requests.filter { it.method == "GET" }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return if (request.method == "POST") batchResponse() else messagesResponse(request)
    }
}

class TestClock(var now: Long = FIXTURE_NOW) {
    fun advance(ms: Long) {
        now += ms
    }
}

internal class CoreHarness(
    val dir: File = Files.createTempDirectory("herald").toFile(),
    val transport: FakeTransport = FakeTransport(),
    val clock: TestClock = TestClock(),
    val options: HeraldOptions = HeraldOptions(),
    val ids: Iterator<String> = generateSequence(1) { it + 1 }.map { "id-$it" }.iterator(),
) {
    val cards = MutableStateFlow<List<ContentCard>>(emptyList())

    fun newCore(context: DeviceContext = fixtureContext) = HeraldCore(
        dir = dir,
        appKey = "pk_test",
        endpoint = "https://herald.test/",
        options = options,
        transport = transport,
        contextProvider = { context },
        sdkVersion = "0.1.0",
        bundleId = "com.lumen.app",
        contentCards = cards,
        clock = { clock.now },
        random = Random(42),
        newId = { ids.next() },
    )
}
