package earth.diego.hindsight.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A designed identity rather than a system-supplied one.
 *
 * Cool paper and graphite, with a single ultramarine that is spent only on three
 * things: the present, what you have already heard, and what is selected. Nothing
 * else in the app is coloured, so colour always means something.
 *
 * Dynamic colour is deliberately not used. Tinting this by whatever wallpaper is
 * set would hand the app's identity to something unrelated to it, and would make
 * the one signal colour stop being a signal. The system light/dark preference is
 * still followed.
 */
@Composable
fun HindsightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.signal,
            onPrimary = Color(0xFF0B0E13),
            background = palette.paper,
            onBackground = palette.graphite,
            surface = palette.paper,
            onSurface = palette.graphite,
            onSurfaceVariant = palette.muted,
            outline = palette.rule,
            // Material derives tinted containers from primary; left alone they
            // spray the signal colour over chips and cards, which is exactly what
            // the one-signal rule forbids.
            surfaceVariant = palette.paper,
            secondaryContainer = palette.rule,
            onSecondaryContainer = palette.graphite,
            surfaceContainerLow = palette.paper,
            surfaceContainer = palette.paper,
            surfaceContainerHigh = palette.rule,
            error = Color(0xFFFF6B6B),
        )
    } else {
        lightColorScheme(
            primary = palette.signal,
            onPrimary = Color(0xFFFFFFFF),
            background = palette.paper,
            onBackground = palette.graphite,
            surface = palette.paper,
            onSurface = palette.graphite,
            onSurfaceVariant = palette.muted,
            outline = palette.rule,
            surfaceVariant = palette.paper,
            secondaryContainer = palette.rule,
            onSecondaryContainer = palette.graphite,
            surfaceContainerLow = palette.paper,
            surfaceContainer = palette.paper,
            surfaceContainerHigh = palette.rule,
            error = Color(0xFFC0392B),
        )
    }

    CompositionLocalProvider(LocalHindsightPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = HindsightType, content = content)
    }
}

/**
 * The named colours the design is described in. Material's roles are too coarse
 * here — the difference between an unplayed trace and a hairline rule matters,
 * and neither is "outline".
 */
data class HindsightPalette(
    val paper: Color,
    val rule: Color,
    val graphite: Color,
    val muted: Color,
    val trace: Color,
    val signal: Color,
)

private val LightPalette = HindsightPalette(
    paper = Color(0xFFF7F8FA),
    rule = Color(0xFFDCE1E8),
    graphite = Color(0xFF161B22),
    muted = Color(0xFF6B7480),
    trace = Color(0xFFB2BBC7),
    signal = Color(0xFF2743E0),
)

private val DarkPalette = HindsightPalette(
    paper = Color(0xFF0C0F14),
    rule = Color(0xFF1E242D),
    graphite = Color(0xFFE9EDF4),
    muted = Color(0xFF828C9B),
    trace = Color(0xFF39414E),
    signal = Color(0xFF7C8CFF),
)

private val LocalHindsightPalette = staticCompositionLocalOf { LightPalette }

val hindsight: HindsightPalette
    @Composable @ReadOnlyComposable get() = LocalHindsightPalette.current

/** Lining figures that share a width, so times and durations align down a column. */
private const val TABULAR = "tnum"

private val HindsightType = Typography(
    // Hour marks on the axis: the loudest thing after the waveforms themselves.
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // Durations, sizes, positions — anything that is data rather than prose.
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
        fontFeatureSettings = TABULAR,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
        fontFeatureSettings = TABULAR,
    ),
)
