package com.herald.sdk

import com.herald.sdk.internal.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {
    @Test
    fun decodesMessagesFixtureAndSkipsUnknownType() {
        val payload = MessageParser.parse(fixture("messages.json"))!!
        assertEquals(listOf("msg_1", "msg_3", "msg_4"), payload.contentCards.map { it.id })
        assertEquals(listOf("msg_2", "msg_5", "msg_6"), payload.inAppMessages.map { it.id })

        val card = payload.contentCards[0]
        assertEquals(ContentCardType.CAPTIONED_IMAGE, card.type)
        assertEquals("Try Plus", card.cta)
        assertEquals(mapOf("campaign" to "upgrade"), card.extras)
        assertTrue(card.pinned)
        assertEquals(listOf("upgrade"), card.categories)
        assertEquals(FIXTURE_NOW, card.createdAt)
        assertNull(card.expiresAt)
        assertEquals("dec_1", card.decisionId)
        assertEquals(ContentCardType.IMAGE_ONLY, payload.contentCards[2].type)
        assertNull(payload.contentCards[2].title)

        val modal = payload.inAppMessages[0]
        assertEquals(InAppMessageType.MODAL, modal.type)
        assertEquals(10, modal.priority)
        assertEquals(TriggerType.EVENT, modal.trigger.type)
        assertEquals("workout_completed", modal.trigger.event)
        assertEquals(PropertyFilter("minutes", FilterOperator.GT, 20), modal.trigger.propertyFilters.single())
        assertEquals(ReEligibilityMode.ONCE, modal.reEligibility.mode)
        assertEquals(listOf("b1", "b2"), modal.content.buttons.map { it.id })
        assertEquals(ButtonAction(ButtonActionType.DEEPLINK, "lumen://plus"), modal.content.buttons[0].action)
        assertEquals(ButtonActionType.CLOSE, modal.content.buttons[1].action.type)
        assertEquals("dec_2", modal.decisionId)

        val slideup = payload.inAppMessages[1]
        assertEquals(ReEligibility(ReEligibilityMode.AFTER_HOURS, 24.0), slideup.reEligibility)
        assertEquals("#101828", slideup.style.backgroundColor)
        assertEquals(12.0, slideup.style.cornerRadius!!, 0.0)
        assertEquals(6.0, slideup.content.dismissAfterSeconds!!, 0.0)
        assertEquals(TriggerType.PUSH_OPEN, payload.inAppMessages[2].trigger.type)
        assertTrue(payload.inAppMessages[2].content.html!!.contains("herald.close()"))
    }

    @Test
    fun decodesEmptyFixture() {
        val payload = MessageParser.parse(fixture("messages-empty.json"))!!
        assertTrue(payload.contentCards.isEmpty())
        assertTrue(payload.inAppMessages.isEmpty())
    }

    @Test
    fun skipsMalformedItemsAndKeepsTheRest() {
        val body = """
            {"data":{"contentCards":[
              {"id":"no_type","title":"x","createdAt":"2026-11-09T14:00:00.000Z"},
              {"type":"classic","createdAt":"2026-11-09T14:00:00.000Z"},
              {"id":"bad_date","type":"classic","createdAt":"yesterday"},
              "not an object",
              {"id":"ok","type":"classic","createdAt":"2026-11-09T14:00:00+00:00"}
            ],"inAppMessages":[
              {"id":"no_event","type":"modal","trigger":{"type":"event"},"reEligibility":{"mode":"once"},"content":{}},
              {"id":"bad_op","type":"modal","trigger":{"type":"event","event":"e","propertyFilters":[{"key":"k","op":"regex","value":1}]},"reEligibility":{"mode":"once"},"content":{}},
              {"id":"html_no_html","type":"html","trigger":{"type":"session_start"},"reEligibility":{"mode":"once"},"content":{}},
              {"id":"hours_missing","type":"modal","trigger":{"type":"session_start"},"reEligibility":{"mode":"after_hours"},"content":{}},
              {"id":"ok","type":"full","trigger":{"type":"session_start"},"reEligibility":{"mode":"once"},"content":{"body":"hi"}}
            ]}}
        """.trimIndent()
        val payload = MessageParser.parse(body)!!
        assertEquals(listOf("ok"), payload.contentCards.map { it.id })
        assertEquals(listOf("ok"), payload.inAppMessages.map { it.id })
    }

    @Test
    fun unusableEnvelopeReturnsNull() {
        assertNull(MessageParser.parse("<html>"))
        assertNull(MessageParser.parse("""{"error":"nope"}"""))
    }
}
