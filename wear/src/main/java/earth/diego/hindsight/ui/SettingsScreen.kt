package earth.diego.hindsight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import earth.diego.hindsight.data.Accent
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.data.WaveStyle

/**
 * Everything configurable, one swipe from the wave. Ordered by how often it is
 * changed: look first, then the one number that changes what Save captures.
 */
@Composable
fun SettingsScreen(
    retention: Retention,
    waveStyle: WaveStyle,
    accent: Accent,
    resolvedAccent: Color,
    onRetention: (Retention) -> Unit,
    onWaveStyle: (WaveStyle) -> Unit,
    onAccent: (Accent) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { ListHeader { Text("Wave") } }
            items(WaveStyle.entries.toList()) { style ->
                RadioButton(
                    selected = style == waveStyle,
                    onSelect = { onWaveStyle(style) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    label = { Text(style.label) },
                    secondaryLabel = {
                        Text(style.description, style = MaterialTheme.typography.labelSmall)
                    },
                )
            }

            item { ListHeader { Text("Colour") } }
            items(Accent.entries.toList()) { option ->
                RadioButton(
                    selected = option == accent,
                    onSelect = { onAccent(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    label = { Text(option.label) },
                    // A swatch says more than the name does.
                    icon = {
                        Box(
                            Modifier
                                .size(18.dp)
                                .background(option.color ?: resolvedAccent, CircleShape),
                        )
                    },
                )
            }

            item { ListHeader { Text("Save last") } }
            items(Retention.entries.toList()) { option ->
                RadioButton(
                    selected = option == retention,
                    onSelect = { onRetention(option) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    label = { Text(option.label) },
                    // Storage is the real trade-off behind this choice, so name it.
                    secondaryLabel = {
                        Text(
                            "~%.1f MB".format(option.approxStorageMb),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}
