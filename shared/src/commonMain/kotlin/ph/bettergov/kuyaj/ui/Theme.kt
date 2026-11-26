package ph.bettergov.kuyaj.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.composeunstyled.ProvideContentColor
import com.composeunstyled.ProvideTextStyle
import org.jetbrains.compose.resources.Font
import ph.bettergov.kuyaj.resources.Res
import ph.bettergov.kuyaj.resources.figtree

// Palette from chat.bettergov.ph (app/globals.css), cooled slightly for a native surface.
@Immutable
data class KuyaColors(
    val background: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val primaryText: Color,
    val border: Color,
    val success: Color,
    val warning: Color,
    val warningSoft: Color,
    val heroStart: Color,
    val heroEnd: Color,
    /** The sun of the Philippine flag, used sparingly as a highlight on the blue hero. */
    val sun: Color,
    val dark: Boolean,
)

private val Light = KuyaColors(
    background = Color(0xFFF4F8FE),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEDF3FC),
    ink = Color(0xFF16263F),
    muted = Color(0xFF5A6E8C),
    faint = Color(0xFF8A9BB4),
    primary = Color(0xFF075DE4),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFE6F0FF),
    primaryText = Color(0xFF0754C5),
    border = Color(0xFFDDE6F3),
    success = Color(0xFF15A34A),
    warning = Color(0xFF8A5300),
    warningSoft = Color(0xFFFFF4DF),
    heroStart = Color(0xFF0A63F0),
    heroEnd = Color(0xFF0646B8),
    sun = Color(0xFFFCD116),
    dark = false,
)

private val Dark = KuyaColors(
    background = Color(0xFF0B1220),
    surface = Color(0xFF131D30),
    surfaceSunken = Color(0xFF0F1828),
    ink = Color(0xFFE6EDF8),
    muted = Color(0xFF9FB0C9),
    faint = Color(0xFF6E809C),
    primary = Color(0xFF3D82F5),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFF16284A),
    primaryText = Color(0xFF8DB6FF),
    border = Color(0xFF223250),
    success = Color(0xFF34D27A),
    warning = Color(0xFFF2B35B),
    warningSoft = Color(0xFF2E2310),
    heroStart = Color(0xFF123B87),
    heroEnd = Color(0xFF0B2A63),
    sun = Color(0xFFFCD116),
    dark = true,
)

@Composable
private fun figtree(weight: Int) = Font(
    Res.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Figtree, a variable font, at the four weights the app uses. Resources load in composition, hence the local. */
val LocalFigtree = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/** One type scale for the whole app. */
object Type {
    private val f @Composable get() = LocalFigtree.current
    val hero @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp)
    val figure @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp)
    val display @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp)
    val title @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp)
    val body @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium @Composable get() = body.copy(fontWeight = FontWeight.Medium)
    val small @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val smallMedium @Composable get() = small.copy(fontWeight = FontWeight.Medium)
    val caption @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val label @Composable get() = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp)
}

val LocalKuyaColors = staticCompositionLocalOf { Light }

@Composable
fun KuyaJTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val figtree = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))
    CompositionLocalProvider(LocalKuyaColors provides c, LocalFigtree provides figtree) {
        ProvideTextStyle(Type.body) {
            ProvideContentColor(c.ink, content)
        }
    }
}
