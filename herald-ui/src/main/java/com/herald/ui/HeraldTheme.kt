package com.herald.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors used by the default card and in-app views. */
@Immutable
data class HeraldColors(
    /** Feed and full-screen message background. */
    val background: Color,
    /** Card, slideup and modal surface. */
    val surface: Color,
    /** Primary text on [surface] and [background]. */
    val onSurface: Color,
    /** Secondary text (card body, captions). */
    val onSurfaceVariant: Color,
    /** Button and call-to-action color. */
    val accent: Color,
    /** Text on [accent]. */
    val onAccent: Color,
    /** Hairline borders and image placeholders. */
    val outline: Color,
    /** Dim layer behind modal messages. */
    val scrim: Color,
) {
    companion object {
        val Light = HeraldColors(
            background = Color(0xFFF7F7F8),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF101828),
            onSurfaceVariant = Color(0xFF475467),
            accent = Color(0xFF2E5BFF),
            onAccent = Color(0xFFFFFFFF),
            outline = Color(0xFFE4E7EC),
            scrim = Color(0x99000000),
        )
        val Dark = HeraldColors(
            background = Color(0xFF0C111D),
            surface = Color(0xFF161B26),
            onSurface = Color(0xFFF5F5F6),
            onSurfaceVariant = Color(0xFF94969C),
            accent = Color(0xFF6E8BFF),
            onAccent = Color(0xFF0C111D),
            outline = Color(0xFF333741),
            scrim = Color(0xB3000000),
        )
    }
}

/** Text styles used by the default views. Sizes are in sp, so the system font scale applies. */
@Immutable
data class HeraldTypography(
    val title: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    val button: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    val caption: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
)

/**
 * The look of Herald's default views. Provide it to [ContentCardFeed] with
 * `CompositionLocalProvider(LocalHeraldTheme provides theme)` and pass it to [HeraldInAppPresenter].
 * Style fields set on an in-app message override the matching theme values for that message.
 */
@Immutable
data class HeraldTheme(
    val lightColors: HeraldColors = HeraldColors.Light,
    val darkColors: HeraldColors = HeraldColors.Dark,
    val typography: HeraldTypography = HeraldTypography(),
    val cornerRadius: Dp = 12.dp,
    /** Base spacing unit: outer padding of cards and messages. */
    val spacing: Dp = 16.dp,
    /** true or false forces dark or light colors; null follows the system setting. */
    val darkMode: Boolean? = null,
) {
    /** The colors for the current light or dark setting. */
    @Composable
    fun colors(): HeraldColors = if (darkMode ?: isSystemInDarkTheme()) darkColors else lightColors
}

/** The theme the default content card views read. */
val LocalHeraldTheme = staticCompositionLocalOf { HeraldTheme() }
