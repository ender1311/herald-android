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
    private fun cached(version: String, settings: String, appKey: String = "pk_test") =
        """{"appKey":"$appKey","data":{"version":"$version","settings":$settings}}"""
    private fun types(h: CoreHarness) =
        h.transport.batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("type") } } }
    private fun names(h: CoreHarness) =
        h.transport.batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } }

    @Test
    fun theCachedConfigAppliesAtLaunchBeforeTheFetch() {
        val h = CoreHarness()
        File(h.dir, "herald_remote_config.json").writeText(cached("v1", """{"flushIntervalSeconds":60,"dataCollection":"minimal"}"""))
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
            HttpResponse(200, emptyMap(), body("v2", """{"foregroundSessionTimeoutSeconds":120,"trackDeepLinks":false,"trackExternalPushes":false,"flushIntervalSeconds":"x"}"""))
        }
        var version: String? = null
        val core = h.newCore(onRemoteConfigVersion = { version = it })
        core.fetchConfig()
        assertEquals(2L, core.options.foregroundTimeoutMinutes)
        assertFalse(core.options.trackDeepLinks)
        assertFalse(core.options.trackExternalPushes)
        assertEquals("a mistyped value keeps the app's", 15L, core.options.flushIntervalSeconds)
        assertEquals(30L, core.options.backgroundTimeoutMinutes)
        assertEquals("v2", version)
        assertEquals("v2", RemoteConfig.parse(File(h.dir, "herald_remote_config.json").readText(), "pk_test")?.version)

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

    @Test
    fun aCachedKillSwitchAppliesAtLaunch() {
        val h = CoreHarness()
        File(h.dir, "herald_remote_config.json").writeText(cached("off", """{"enabled":false}"""))
        val core = h.newCore()
        core.track("early", emptyMap())
        core.flush()
        assertEquals(0, core.queue.size)
        assertTrue(h.transport.batches.isEmpty())
    }

    @Test
    fun anotherAppKeysCachedConfigIsIgnored() {
        val h = CoreHarness()
        File(h.dir, "herald_remote_config.json").writeText(cached("off", """{"enabled":false}""", appKey = "pk_staging"))
        var version: String? = "unset"
        val core = h.newCore(onRemoteConfigVersion = { version = it })
        assertNull(version)
        core.track("a", emptyMap())
        assertEquals("session_start and the event", 2, core.queue.size)
    }

    @Test
    fun aPushTokenRegisteredWhileOffIsSentWhenCollectionResumes() {
        val h = CoreHarness()
        val core = h.newCore()
        core.applyRemote(RemoteConfig("off", JSONObject().put("enabled", false)))
        core.setPushToken("tok")
        core.setSubscription(SubscriptionChannel.EMAIL, SubscriptionState.UNSUBSCRIBED)
        core.track("dropped", emptyMap())
        core.flush()
        assertTrue("nothing is sent while off", h.transport.batches.isEmpty())
        core.applyRemote(RemoteConfig("on", JSONObject()))
        core.flush()
        val sent = types(h)
        assertTrue(sent.toString(), "push_token" in sent && "subscription" in sent)
        assertFalse("track" in sent)
    }

    @Test
    fun aSlowConfigFetchDoesNotDelaySessionStart() {
        val released = java.util.concurrent.CountDownLatch(1)
        val h = CoreHarness()
        h.transport.configResponse = {
            released.await(5, java.util.concurrent.TimeUnit.SECONDS)
            HttpResponse(200, emptyMap(), body("late", """{"flushIntervalSeconds":60}"""))
        }
        val applied = java.util.concurrent.CountDownLatch(1)
        val core = com.herald.sdk.internal.HeraldCore(
            dir = h.dir, appKey = "pk_test", endpoint = "https://herald.test/", options = h.options,
            transport = h.transport, contextProvider = { fixtureContext }, sdkVersion = "0.4.0",
            clock = { h.clock.now }, newId = { java.util.UUID.randomUUID().toString() },
            background = { work -> Thread(work).start() },
        )
        core.runOnSdkThread = { block -> synchronized(core) { block() }; applied.countDown() }
        synchronized(core) {
            core.fetchConfig()
            core.onForeground() // runs while the fetch is still waiting
            assertEquals("session_start", core.queue.peekBatch(100).first().event.getString("type"))
            assertEquals("not applied yet", 10L, core.options.flushIntervalSeconds)
        }
        released.countDown()
        assertTrue(applied.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(60L, core.options.flushIntervalSeconds)
    }

    @Test
    fun theConsentRecordMatchesTheSharedFixture() {
        val h = CoreHarness()
        val core = h.newCore()
        core.setConsent(ConsentBasis.CONSENT, "privacy-policy", "2026-11")
        core.track("a", emptyMap())
        core.flush()
        val expected = JSONObject(fixture("batch-consent.json")).getJSONObject("context").getJSONObject("consent")
        assertTrue(h.transport.batches.last().getJSONObject("context").getJSONObject("consent").similar(expected))
    }
}
