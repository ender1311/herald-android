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
    fun sampleSizeBoundsWidthHeightAndPixels() {
        assertEquals(1, HeraldImages.sampleSize(800, 450, 1000, 2000))
        assertEquals(2, HeraldImages.sampleSize(2000, 1000, 1000, 2000))
        assertEquals(4, HeraldImages.sampleSize(4000, 2000, 1000, 2000))
        // Tall image: height drives the sample even though the width already fits.
        val tall = HeraldImages.sampleSize(1080, 25000, 1080, 2160)
        assertEquals(16, tall)
        assertTrue(25000 / tall <= 2160)
        // Huge square under a generous box: the pixel budget caps it.
        val huge = HeraldImages.sampleSize(10000, 10000, 10000, 20000)
        assertTrue((10000L / huge) * (10000L / huge) <= HeraldImages.MAX_PIXELS)
    }

    @Test
    fun unboundedWidthClampsToDisplay() {
        assertEquals(1080, HeraldImages.clampWidth(Int.MAX_VALUE, 1080))
        assertEquals(1080, HeraldImages.clampWidth(0, 1080))
        assertEquals(500, HeraldImages.clampWidth(500, 1080))
    }

    @Test
    fun onlyTappedMainFrameNavigationsAreClicks() {
        assertTrue(isUserNavigation(isMainFrame = true, hasGesture = true))
        assertFalse(isUserNavigation(isMainFrame = true, hasGesture = false)) // redirect, meta refresh
        assertFalse(isUserNavigation(isMainFrame = false, hasGesture = true)) // iframe
    }
}
