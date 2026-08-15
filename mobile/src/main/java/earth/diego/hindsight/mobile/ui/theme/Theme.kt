package earth.diego.hindsight.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A warm, slightly desaturated accent — this is an app about speech and memory,
// not a media player, so it should read as calm rather than energetic.
private val Seed = Color(0xFF7C6BF0)

private val LightScheme = lightColorScheme(
    primary = Seed,
    secondary = Color(0xFF5F5C71),
    tertiary = Color(0xFF7A5368),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCBC0FF),
    secondary = Color(0xFFC8C4DC),
    tertiary = Color(0xFFE9B9CF),
)

/**
 * Follows the system light/dark setting, and the user's wallpaper palette where
 * the platform offers one. The previous build forced dark unconditionally, which
 * is a choice the app has no business making for the user.
 */
@Composable
fun HindsightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
