package com.herald.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAutoplayTest {
    @Test
    fun autoplaysOnlyWhenHalfVisibleEnabledAndAnimationsAllowed() {
        assertTrue(VideoAutoplay.shouldPlay(visibleFraction = 0.5f, autoplay = true, reducedMotion = false))
        assertTrue(VideoAutoplay.shouldPlay(visibleFraction = 1f, autoplay = true, reducedMotion = false))
        assertFalse("under half visible: paused", VideoAutoplay.shouldPlay(0.49f, autoplay = true, reducedMotion = false))
        assertFalse("off screen: paused", VideoAutoplay.shouldPlay(0f, autoplay = true, reducedMotion = false))
        assertFalse("videoAutoplay off (the default)", VideoAutoplay.shouldPlay(1f, autoplay = false, reducedMotion = false))
        assertFalse("remove animations disables autoplay", VideoAutoplay.shouldPlay(1f, autoplay = true, reducedMotion = true))
    }

    @Test
    fun playButtonLabel() {
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("""<string name="herald_play_video">Play video</string>"""))
    }

    @Test
    fun animationsAreRemovedOnlyWhenTheDurationScaleIsZero() {
        assertTrue(VideoAutoplay.animationsRemoved(0f))
        assertFalse(VideoAutoplay.animationsRemoved(1f))
        assertFalse(VideoAutoplay.animationsRemoved(0.5f))
    }

    @Test
    fun visibleFractionIsClampedAndZeroForEmptyViews() {
        assertEquals(0f, VideoAutoplay.visibleFraction(visibleHeight = 10f, fullHeight = 0), 0f)
        assertEquals(1f, VideoAutoplay.visibleFraction(visibleHeight = 120f, fullHeight = 100), 0f)
        assertEquals(0.25f, VideoAutoplay.visibleFraction(visibleHeight = 25f, fullHeight = 100), 0f)
    }

    @Test
    fun onePlayAndOneCompletePerPlay() {
        val events = VideoPlayEvents()
        assertTrue("first start: video_play", events.started())
        assertFalse("resume during the same play", events.started())
        assertTrue("end: video_complete", events.ended())
        assertFalse("a second end (or the next loop) is not another complete", events.ended())
        assertTrue("replay after the end: a new play", events.started())
        assertTrue(events.ended())
    }

    @Test
    fun endBeforeStartIsIgnored() {
        assertFalse(VideoPlayEvents().ended())
    }
}
