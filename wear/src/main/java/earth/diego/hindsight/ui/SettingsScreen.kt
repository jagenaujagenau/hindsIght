package earth.diego.hindsight.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import earth.diego.hindsight.data.Accent
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.data.Sensitivity
import earth.diego.hindsight.data.WaveStyle

/** Recording controls first; decorative choices live one level deeper. */
@Composable
fun SettingsScreen(
    recording: Boolean,
    onToggleRecording: () -> Unit,
    retention: Retention,
    waveStyle: WaveStyle,
    accent: Accent,
    sensitivity: Sensitivity,
    resolvedAccent: Color,
    onRetention: (Retention) -> Unit,
    onWaveStyle: (WaveStyle) -> Unit,
    onAccent: (Accent) -> Unit,
    onSensitivity: (Sensitivity) -> Unit,
) {
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(appearanceOpen) { appearanceOpen = false }
    if (appearanceOpen) {
        AppearanceSettings(waveStyle, accent, sensitivity, resolvedAccent, onWaveStyle, onAccent, onSensitivity) {
            appearanceOpen = false
        }
        return
    }

    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text("Recording") } }
            item {
                Button(onClick = onToggleRecording, modifier = Modifier.fillMaxWidth()) {
                    Text(if (recording) "Stop listening" else "Start listening")
                }
            }
            item { ListHeader { Text("Save last") } }
            items(Retention.entries, key = { it.name }) { option ->
                RadioButton(
                    selected = option == retention,
                    onSelect = { onRetention(option) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    label = { Text(option.label) },
                    secondaryLabel = { Text("~%.1f MB".format(option.approxStorageMb)) },
                )
            }
            item {
                Button(onClick = { appearanceOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Appearance")
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettings(
    waveStyle: WaveStyle,
    accent: Accent,
    sensitivity: Sensitivity,
    resolvedAccent: Color,
    onWaveStyle: (WaveStyle) -> Unit,
    onAccent: (Accent) -> Unit,
    onSensitivity: (Sensitivity) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Recording settings") }
            }
            item { ListHeader { Text("Wave") } }
            items(WaveStyle.entries, key = { "wave-${it.name}" }) { style ->
                RadioButton(
                    selected = style == waveStyle,
                    onSelect = { onWaveStyle(style) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    label = { Text(style.label) },
                )
            }
            item { ListHeader { Text("Colour") } }
            items(Accent.entries, key = { "accent-${it.name}" }) { option ->
                RadioButton(
                    selected = option == accent,
                    onSelect = { onAccent(option) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    label = { Text(option.label) },
                    icon = {
                        Box(Modifier.size(18.dp).background(option.color ?: resolvedAccent, CircleShape))
                    },
                )
            }
            item { ListHeader { Text("Wave sensitivity") } }
            item {
                Text(
                    "Display only; recording unchanged.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            items(Sensitivity.entries, key = { "sensitivity-${it.name}" }) { option ->
                RadioButton(
                    selected = option == sensitivity,
                    onSelect = { onSensitivity(option) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    label = { Text(option.label) },
                    secondaryLabel = { Text(option.description, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}
