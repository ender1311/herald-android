package com.herald.sdk

import com.herald.sdk.internal.HttpResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LifecycleTest {
    /** Everything sent so far plus what is still queued, in order. */
    private fun events(h: CoreHarness, core: com.herald.sdk.internal.HeraldCore): List<JSONObject> =
        h.transport.batches.flatMap { b -> b.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } } } +
            core.queue.peekBatch(100).map { it.event }

    private fun JSONObject.asMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> get(k).let { if (it is Int) it.toLong() else it } }

    private fun names(events: List<JSONObject>) = events.map { it.optString("name").ifEmpty { it.getString("type") } }

    @Test
    fun installThenNormalLaunchThenUpdate() {
        val h = CoreHarness(lifecycle = true)
        var core = h.newCore()
        var sent = events(h, core)
        assertEquals("a launch alone starts no session", listOf("\$app_installed"), names(sent))
        assertEquals(mapOf("version" to "4.2.0", "build" to "812"), sent[0].getJSONObject("properties").asMap())
        core.flush()

        core = CoreHarness(dir = h.dir, transport = h.transport, clock = h.clock, lifecycle = true).newCore()
        assertEquals("same version: nothing new", 1, names(events(h, core)).size)

        core = CoreHarness(dir = h.dir, transport = h.transport, clock = h.clock, lifecycle = true)
            .newCore(context = fixtureContext.copy(appVersion = "4.3.0", appBuild = "900"))
        sent = events(h, core)
        assertEquals("\$app_updated", names(sent).last())
        val props = sent.last().getJSONObject("properties")
        assertEquals(listOf("4.3.0", "900", "4.2.0", "812"), listOf("version", "build", "previous_version", "previous_build").map(props::getString))
    }

    @Test
    fun stateFromAnOlderSdkOnlyStoresTheVersion() {
        val dir = java.nio.file.Files.createTempDirectory("herald").toFile()
        File(dir, "herald_state.json").writeText("""{"deviceId":"d1"}""")
        val h = CoreHarness(dir = dir, lifecycle = true)
        val core = h.newCore()
        assertEquals(emptyList<String>(), names(events(h, core)))
        assertEquals("4.2.0", JSONObject(File(dir, "herald_state.json").readText()).getString("appVersion"))
    }

    @Test
    fun openedForegroundAndBackground() {
        val h = CoreHarness(lifecycle = true)
        val core = h.newCore()
        core.onPushOpened("m1", "d1")
        core.onForeground()
        core.onBackground()
        core.onForeground()
        val sent = events(h, core)
        assertEquals(
            listOf("\$app_installed", "\$app_opened", "\$app_foreground", "\$app_background", "\$app_foreground"),
            names(sent).filter { it.startsWith("$") },
        )
        assertEquals(true, sent.first { it.optString("name") == "\$app_opened" }.getJSONObject("properties").getBoolean("from_push"))
    }

    @Test
    fun openedWithoutPushAndOffSwitch() {
        var h = CoreHarness(lifecycle = true)
        var core = h.newCore()
        core.onForeground()
        assertEquals(false, events(h, core).first { it.optString("name") == "\$app_opened" }.getJSONObject("properties").getBoolean("from_push"))

        h = CoreHarness() // lifecycle off
        core = h.newCore()
        core.onForeground()
        core.onBackground()
        assertEquals(listOf("session_start"), names(events(h, core)))
    }

    @Test
    fun lifecycleEventsSendInMinimalMode() {
        val h = CoreHarness(options = HeraldOptions(deviceDataCollection = DeviceDataCollection.MINIMAL))
        val core = h.newCore()
        core.onForeground()
        assertEquals(listOf("\$app_installed", "session_start", "\$app_opened", "\$app_foreground"), names(events(h, core)))
    }

    @Test
    fun deepLinkLeavesOutQueryByDefault() {
        val url = "lumen://plus/offer/7?code=abc#top"
        fun props(options: HeraldOptions, source: String = "link"): JSONObject? {
            val h = CoreHarness(options = options)
            val core = h.newCore()
            core.deepLinkOpened(url, source)
            return events(h, core).lastOrNull { it.optString("name") == "\$deep_link_opened" }?.getJSONObject("properties")
        }
        val off = HeraldOptions(trackLifecycleEvents = false)
        assertEquals(mapOf("scheme" to "lumen", "host" to "plus", "source" to "push", "path" to "/offer/7"), props(off, "push")?.asMap())
        assertEquals("code=abc", props(off.copy(trackDeepLinkQuery = true))?.getString("query"))
        assertEquals(
            "minimal: scheme and host only",
            mapOf("scheme" to "lumen", "host" to "plus", "source" to "link"),
            props(off.copy(deviceDataCollection = DeviceDataCollection.MINIMAL, trackDeepLinkQuery = true))?.asMap(),
        )
        assertEquals(null, props(off.copy(trackDeepLinks = false)))
    }

    @Test
    fun goingToTheBackgroundAfterAnIdleForegroundStartsNoSession() {
        val h = CoreHarness(lifecycle = true)
        val core = h.newCore()
        core.onForeground()
        h.clock.advance(35 * 60_000) // past the 30-minute foreground timeout, nothing tracked
        core.onBackground()
        val sent = events(h, core)
        assertEquals(listOf("\$app_installed", "session_start", "\$app_opened", "\$app_foreground", "\$app_background"), names(sent))
        assertEquals(sent[1].getString("sessionId"), sent.last().getString("sessionId"))
    }

    @Test
    fun aConfigurationChangeIsNoTransition() {
        val t = com.herald.sdk.internal.ForegroundTracker()
        assertEquals(true, t.onStarted()) // app opens
        assertEquals(false, t.onStopped(isChangingConfigurations = true)) // rotation, dark mode, resize
        assertEquals(false, t.onStarted()) // the recreated activity
        assertEquals(false, t.onStarted()) // a second activity
        assertEquals(false, t.onStopped(isChangingConfigurations = false))
        assertEquals(true, t.onStopped(isChangingConfigurations = false)) // Home
        assertEquals(true, t.onStarted())
    }

    @Test
    fun coldStartAfterTheBackgroundUsesTheBackgroundTimeout() {
        val options = HeraldOptions(trackLifecycleEvents = false, foregroundSessionTimeoutMinutes = 60, backgroundSessionTimeoutMinutes = 5)
        val h = CoreHarness(options = options)
        var core = h.newCore()
        core.onForeground()
        core.onBackground()
        h.clock.advance(10 * 60_000)
        core = CoreHarness(dir = h.dir, transport = h.transport, clock = h.clock, options = options).newCore() // killed in the background
        core.onForeground()
        assertEquals(listOf("session_start", "session_end", "session_start"), names(events(h, core)))
    }

    @Test
    fun deepLinkWithoutAHostSendsTheSchemeOnly() {
        val cases = mapOf(
            "mailto:a@b.com?subject=hi" to mapOf("scheme" to "mailto", "source" to "link"),
            "tel:+15551234567" to mapOf("scheme" to "tel", "source" to "link"),
            "https://x.com/a?b" to mapOf("scheme" to "https", "host" to "x.com", "path" to "/a", "query" to "b", "source" to "link"),
            "myapp://screen/x" to mapOf("scheme" to "myapp", "host" to "screen", "path" to "/x", "source" to "link"),
            "myapp://screen/x;jsessionid=s" to mapOf("scheme" to "myapp", "host" to "screen", "path" to "/x", "source" to "link"),
            "myapp://deep_link/x" to mapOf("scheme" to "myapp", "host" to "deep_link", "path" to "/x", "source" to "link"),
            "https://x.com/a b" to mapOf("scheme" to "https", "source" to "link"),
        )
        for ((url, expected) in cases) {
            val h = CoreHarness(options = HeraldOptions(trackLifecycleEvents = false, trackDeepLinkQuery = true))
            val core = h.newCore()
            core.deepLinkOpened(url, "link")
            assertEquals(url, expected, events(h, core).last().getJSONObject("properties").asMap())
        }
    }

    @Test
    fun sessionContinuesAcrossARestartWithinTheTimeout() {
        val h = CoreHarness()
        var core = h.newCore()
        core.track("a", emptyMap())
        core.onBackground() // persists the last activity and sends
        h.clock.advance(10 * 60_000)
        core = CoreHarness(dir = h.dir, transport = h.transport, clock = h.clock, options = h.options).newCore()
        core.track("b", emptyMap())
        core.flush()
        h.clock.advance(31 * 60_000)
        core = CoreHarness(dir = h.dir, transport = h.transport, clock = h.clock, options = h.options).newCore()
        core.track("c", emptyMap())
        val sent = events(h, core)
        assertEquals(listOf("session_start", "a", "b", "session_end", "session_start", "c"), names(sent))
        assertEquals(sent[0].getString("sessionId"), sent[2].getString("sessionId"))
        assertEquals(mapOf("session_index" to 1L, "previous_session_id" to JSONObject.NULL), sent[0].getJSONObject("properties").asMap())
        val second = sent[4].getJSONObject("properties")
        assertEquals(2L, second.getLong("session_index"))
        assertEquals(sent[0].getString("sessionId"), second.getString("previous_session_id"))
    }

    @Test
    fun separateForegroundAndBackgroundTimeouts() {
        val h = CoreHarness(options = HeraldOptions(trackLifecycleEvents = false, foregroundSessionTimeoutMinutes = 60, backgroundSessionTimeoutMinutes = 5))
        val core = h.newCore()
        core.onForeground()
        h.clock.advance(40 * 60_000)
        core.track("foreground_idle", emptyMap()) // within the foreground hour
        core.onBackground()
        h.clock.advance(6 * 60_000)
        core.onForeground() // over the background 5 minutes
        val sent = events(h, core)
        assertEquals(listOf("session_start", "foreground_idle", "session_end", "session_start"), names(sent))
        assertEquals(2L, sent[3].getJSONObject("properties").getLong("session_index"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedSessionTimeoutSetsBoth() {
        for (options in listOf(HeraldOptions(sessionTimeoutMinutes = 2), HeraldOptions().copy(sessionTimeoutMinutes = 2))) {
            assertEquals(2L, options.foregroundTimeoutMinutes)
            assertEquals(2L, options.backgroundTimeoutMinutes)
        }
        assertEquals(7L, HeraldOptions(sessionTimeoutMinutes = 2, foregroundSessionTimeoutMinutes = 7).foregroundTimeoutMinutes)
    }

    @Test
    fun queuedEventsOlderThanTheMaxAgeAreDroppedAndCounted() {
        val h = CoreHarness(options = HeraldOptions(trackLifecycleEvents = false, maxQueuedEventAgeDays = 1))
        val core = h.newCore()
        h.transport.batchResponse = { throw java.io.IOException("offline") }
        core.track("old", emptyMap())
        core.flush() // offline: stays queued
        h.transport.batchResponse = { HttpResponse(200, emptyMap(), """{"data":{"accepted":1,"duplicates":0}}""") }
        h.clock.advance(2 * 86_400_000L)
        core.track("new", emptyMap())
        core.flush()
        val body = h.transport.batches.last()
        val a = body.getJSONArray("events")
        assertEquals(listOf("session_start", "new"), names((0 until a.length()).map { a.getJSONObject(it) }))
        assertEquals("the first session's start, old and its end were over a day old", 3, body.getInt("dropped"))
    }
}
