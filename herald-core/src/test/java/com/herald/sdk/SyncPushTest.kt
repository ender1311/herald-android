package com.herald.sdk

import com.herald.sdk.internal.HttpResponse
import com.herald.sdk.push.HeraldPushPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Herald sync pushes (`herald_sync` = "1"); matches `SyncPushTests.swift`. */
class SyncPushTest {
    private val h = CoreHarness()
    private val core = h.newCore()

    @Test
    fun syncPushRefreshesWithEtagAndDebounces() {
        core.identify("u_1", emptyMap())
        val gets = h.transport.gets.size
        h.transport.messagesResponse = { HttpResponse(304, emptyMap(), "") }

        core.syncFromPush()
        assertEquals(gets + 1, h.transport.gets.size)
        assertEquals("\"v1\"", h.transport.gets.last().headers["If-None-Match"])

        h.clock.advance(29_000)
        core.syncFromPush()
        assertEquals("a second sync push within 30 s is skipped", gets + 1, h.transport.gets.size)

        h.clock.advance(2_000)
        core.syncFromPush()
        assertEquals(gets + 2, h.transport.gets.size)
        assertEquals(listOf("msg_1", "msg_3", "msg_4"), h.cards.value.map { it.id })
    }

    @Test
    fun syncPushDoesNothingWhileDisabled() {
        core.setEnabled(false)
        val before = h.transport.requests.size
        core.syncFromPush()
        assertEquals(before, h.transport.requests.size)
    }

    @Test
    fun syncPushIsNotAnOpenablePush() {
        assertNull(HeraldPushPayload.fromData(mapOf("herald_sync" to "1", "herald_message_id" to "m1")))
        assertNotNull(HeraldPushPayload.fromData(mapOf("herald_message_id" to "m1")))
    }

    @Test
    fun handleRemoteMessageAcceptsOnlySyncPushes() {
        assertTrue(Herald.handleRemoteMessage(mapOf("herald_sync" to "1")))
        assertFalse(Herald.handleRemoteMessage(mapOf("herald_message_id" to "m1", "title" to "Hi")))
        assertFalse(Herald.handleRemoteMessage(mapOf("herald_sync" to "0")))
    }
}
