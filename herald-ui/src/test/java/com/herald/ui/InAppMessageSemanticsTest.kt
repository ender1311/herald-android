package com.herald.ui

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppMessageSemanticsTest {
    @Test
    fun messageHeaderIsAHeadingForTalkBack() {
        val config = SemanticsConfiguration().apply(headerSemantics)
        assertTrue(SemanticsProperties.Heading in config)
    }
}
