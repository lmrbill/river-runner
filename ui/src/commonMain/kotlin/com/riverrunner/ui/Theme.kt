package com.riverrunner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.riverrunner.i18n.LocalStrings
import com.riverrunner.i18n.Strings

/**
 * Fonts supplied by the app. [numeric] is Barlow Condensed for numbers; [text], when set, is the
 * family for all other text (the web app has no system fonts and brings a Chinese font).
 */
class AppFonts(val numeric: FontFamily, val text: FontFamily? = null)

val LocalFonts = staticCompositionLocalOf { AppFonts(FontFamily.Default) }

@Composable
fun RiverTheme(strings: Strings, deck: DeckStyle = DeckStyle.FOUR_COLOR, fonts: AppFonts, content: @Composable () -> Unit) {
    val base = Typography()
    val typography = fonts.text?.let { f ->
        fun TextStyle.f() = copy(fontFamily = f)
        Typography(
            displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
            headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
            titleLarge = base.titleLarge.f(), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
            bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
            labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
        )
    } ?: base
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Palette.Accent, onPrimary = Palette.Ink, secondary = Palette.Accent, background = Palette.Bg, surface = Palette.Panel),
        typography = typography,
    ) {
        CompositionLocalProvider(LocalStrings provides strings, LocalDeckStyle provides deck, LocalFonts provides fonts) { content() }
    }
}
