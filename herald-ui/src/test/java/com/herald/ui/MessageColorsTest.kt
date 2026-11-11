package com.herald.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** I4: a style background (or button) color without its text color gets black or white by contrast. */
class MessageColorsTest {
    private fun resolve(background: Color?, button: Color?, colors: HeraldColors, text: Color? = null) =
        messageColors(background, text, button, null, 12.dp, colors)

    @Test
    fun partialStyleOverridesPickReadableTextInLightAndDarkMode() {
        for (theme in listOf(HeraldColors.Light, HeraldColors.Dark)) {
            val onWhite = resolve(Color(0xFFFFFFFF), Color(0xFFFFEB3B), theme)
            assertEquals(Color.Black, onWhite.text)
            assertEquals(Color.Black, onWhite.buttonText)
            val onDark = resolve(Color(0xFF101828), Color(0xFF1A237E), theme)
            assertEquals(Color.White, onDark.text)
            assertEquals(Color.White, onDark.buttonText)
            assertEquals("an explicit text color wins", Color.Red, resolve(Color.White, null, theme, text = Color.Red).text)
            val unset = resolve(null, null, theme)
            assertEquals("no style: the theme's colors", theme.onSurface, unset.text)
            assertEquals(theme.onAccent, unset.buttonText)
        }
    }
}
