package com.herald.ui

import com.herald.sdk.ContentCard
import com.herald.sdk.ContentCardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLogicTest {
    private fun card(id: String, pinned: Boolean, createdAt: Long, categories: List<String> = emptyList()) = ContentCard(
        id = id, type = ContentCardType.CLASSIC, title = id, body = null, imageUrl = null, cta = null, url = null,
        extras = emptyMap(), pinned = pinned, dismissible = true, categories = categories, createdAt = createdAt,
        expiresAt = null, viewed = false, clicked = false, dismissed = false, decisionId = null,
    )

    @Test
    fun pinnedFirstThenNewest() {
        val cards = listOf(card("old", false, 1), card("pinnedOld", true, 2), card("new", false, 5), card("pinnedNew", true, 4))
        val ordered = feedCards(cards, { true }, ContentCardFeedDefaults.Order).map { it.id }
        assertEquals(listOf("pinnedNew", "pinnedOld", "new", "old"), ordered)
    }

    @Test
    fun filterApplies() {
        val cards = listOf(card("a", false, 1, listOf("news")), card("b", false, 2, listOf("upgrade")))
        assertEquals(listOf("b"), feedCards(cards, { "upgrade" in it.categories }, ContentCardFeedDefaults.Order).map { it.id })
    }

    @Test
    fun impressionAtHalfVisible() {
        assertTrue(isImpression(50f, 100))
        assertFalse(isImpression(49f, 100))
        assertFalse(isImpression(0f, 0))
    }

    @Test
    fun sampleSizeKeepsAtLeastTargetWidth() {
        assertEquals(1, HeraldImages.sampleSize(800, 1000))
        assertEquals(2, HeraldImages.sampleSize(2000, 1000))
        assertEquals(4, HeraldImages.sampleSize(4000, 1000))
        assertEquals(2, HeraldImages.sampleSize(3999, 1000))
        assertEquals(1, HeraldImages.sampleSize(4000, 0))
    }
}
