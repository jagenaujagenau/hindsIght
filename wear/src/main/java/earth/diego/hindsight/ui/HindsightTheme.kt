package earth.diego.hindsight.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Material 3 Expressive, tinted by the watch face.
 *
 * Wear OS 6 exposes the active watch face's palette, so the app reads as part of
 * the user's own setup rather than an import from a phone. Where that isn't
 * available the M3 baseline scheme is already a good dark theme, so there is no
 * hand-rolled palette to drift.
 */
@Composable
fun HindsightTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = dynamicColorScheme(context) ?: MaterialTheme.colorScheme,
        content = content,
    )
}
