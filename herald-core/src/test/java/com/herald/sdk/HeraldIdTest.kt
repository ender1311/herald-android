package com.herald.sdk

import com.herald.sdk.internal.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The person's User ID from the batch response (`data.heraldId`, batch-response.json). */
class HeraldIdTest {
    @Test
    fun `null before the first sync, then parsed and cached across launches`() {
        val h = CoreHarness()
        val core = h.newCore()
        core.identify("u_123", emptyMap())
        assertNull(h.heraldId.value)

        h.transport.batchResponse = { HttpResponse(200, emptyMap(), fixture("batch-response.json")) }
        core.flush()
        assertEquals("7K3M-9QXA", h.heraldId.value)

        h.heraldId.value = null
        h.newCore()
        assertEquals("7K3M-9QXA", h.heraldId.value)
    }

    @Test
    fun `cleared on reset and not set by anonymous batches`() {
        val h = CoreHarness()
        val core = h.newCore()
        core.identify("u_123", emptyMap())
        h.transport.batchResponse = { HttpResponse(200, emptyMap(), fixture("batch-response.json")) }
        core.flush()

        core.reset()
        assertNull(h.heraldId.value)
        core.flush()
        assertNull(h.heraldId.value)
    }

    @Test
    fun `a null or missing heraldId is no value, not the string null`() {
        for (body in listOf("""{"data":{"accepted":1,"duplicates":0,"heraldId":null}}""", """{"data":{"accepted":1,"duplicates":0}}""")) {
            val h = CoreHarness()
            val core = h.newCore()
            core.identify("u_123", emptyMap())
            h.transport.batchResponse = { HttpResponse(200, emptyMap(), body) }
            core.flush()
            assertNull(h.heraldId.value)
        }
    }
}
