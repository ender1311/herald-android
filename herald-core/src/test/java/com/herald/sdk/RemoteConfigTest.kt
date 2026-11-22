package com.herald.sdk

import com.herald.sdk.internal.HttpResponse
import com.herald.sdk.internal.RemoteConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class RemoteConfigTest {
    private fun body(version: String, settings: String) = """{"data":{"version":"$version","settings":$settings}}"""
    private fun names(h: CoreHarness) =
        h.transport.batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } }

    @Test
    fun theCachedConfigAppliesAtLaunchBeforeTheFetch() {
        val h = CoreHarness()
        File(h.dir, "herald_remote_config.json").writeText(body("v1", """{"flushIntervalSeconds":60,"dataCollection":"minimal"}"""))
        var version: String? = null
        val core = h.newCore(onRemoteConfigVersion = { version = it })
        assertEquals(60L, core.options.flushIntervalSeconds)
        assertEquals(DeviceDataCollection.MINIMAL, core.options.deviceDataCollection)
        assertEquals("v1", version)
        // The launch fetch asks with the cached version; a 304 keeps it.
        core.fetchConfig()
        assertEquals("\"v1\"", h.transport.configs.single().headers["If-None-Match"])
        assertEquals("pk_test", h.transport.configs.single().headers["x-herald-app-key"])
        assertEquals(60L, core.options.flushIntervalSeconds)
    }

    @Test
    fun remoteValuesOverrideAndAbsentOnesFallBack() {
        val h = CoreHarness(options = HeraldOptions(trackLifecycleEvents = false, flushIntervalSeconds = 15))
        h.transport.configResponse = {
            HttpResponse(200, emptyMap(), body("v2", """{"foregroundSessionTimeoutSeconds":120,"trackDeepLinks":false,"flushIntervalSeconds":"x"}"""))
        }
        var version: String? = null
        val core = h.newCore(onRemoteConfigVersion = { version = it })
        core.fetchConfig()
        assertEquals(2L, core.options.foregroundTimeoutMinutes)
        assertFalse(core.options.trackDeepLinks)
        assertEquals("a mistyped value keeps the app's", 15L, core.options.flushIntervalSeconds)
        assertEquals(30L, core.options.backgroundTimeoutMinutes)
        assertEquals("v2", version)
        assertEquals("v2", RemoteConfig.parse(File(h.dir, "herald_remote_config.json").readText())?.version)

        // A failed fetch or an unreadable body keeps it.
        h.transport.configResponse = { throw IOException("offline") }
        core.fetchConfig()
        h.transport.configResponse = { HttpResponse(200, emptyMap(), "nope") }
        core.fetchConfig()
        assertEquals("v2", version)
        assertEquals(2L, core.options.foregroundTimeoutMinutes)
    }

    @Test
    fun theKillSwitchStopsCollectionAndClearsTheQueueUntilTurnedBackOn() {
        val h = CoreHarness()
        val core = h.newCore()
        core.track("before", emptyMap())
        core.applyRemote(RemoteConfig("off", JSONObject().put("enabled", false)))
        assertEquals(0, core.queue.size)
        core.track("during", emptyMap())
        core.flush()
        assertEquals(0, core.queue.size)
        assertTrue(h.transport.batches.isEmpty())
        // Config is still fetched while off.
        h.transport.configResponse = { HttpResponse(200, emptyMap(), body("on", "{}")) }
        core.fetchConfig()
        core.track("after", emptyMap())
        core.flush()
        assertEquals(listOf("after"), names(h).filter { it.isNotEmpty() })
    }

    @Test
    fun theFetchRunsAtLaunchThenAtMostHourlyOnForeground() {
        val h = CoreHarness()
        val core = h.newCore()
        core.fetchConfig() // the facade's launch fetch
        core.onForeground()
        h.clock.advance(59 * 60_000)
        core.onBackground()
        core.onForeground()
        assertEquals(1, h.transport.configs.size)
        h.clock.advance(60_000)
        core.onBackground()
        core.onForeground()
        assertEquals(2, h.transport.configs.size)
    }

    @Test
    fun consentIsPersistedSentInContextThenCleared() {
        val h = CoreHarness()
        var core = h.newCore()
        core.setConsent(ConsentBasis.LEGITIMATE_INTERESTS, "privacy", "x".repeat(101))
        core.track("refused", emptyMap())
        core.flush()
        assertFalse("a document version over 100 characters is refused", h.transport.batches.last().getJSONObject("context").has("consent"))
        core.setConsent(ConsentBasis.LEGITIMATE_INTERESTS, "privacy", "")
        core = CoreHarness(dir = h.dir, transport = h.transport, clock = h.clock).newCore() // persisted across launches
        core.track("a", emptyMap())
        core.flush()
        val consent = h.transport.batches.last().getJSONObject("context").getJSONObject("consent")
        assertEquals(
            mapOf("basis" to "legitimate_interests", "documentId" to "privacy", "recordedAt" to "2026-11-09T14:00:00.000Z"),
            consent.keys().asSequence().associateWith { consent.get(it) },
        )
        core.clearConsent()
        core.track("b", emptyMap())
        core.flush()
        assertNull(h.transport.batches.last().getJSONObject("context").optJSONObject("consent"))
    }
}
