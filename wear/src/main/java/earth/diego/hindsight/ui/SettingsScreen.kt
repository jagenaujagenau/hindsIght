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
import earth.diego.hindsight.data.SessionLimit
import earth.diego.hindsight.service.StorageStatus
import earth.diego.hindsight.service.SaveState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import earth.diego.hindsight.sync.SyncState

private enum class SettingsPage { MAIN, APPEARANCE, TIMER, SYNC }

/** Recording controls first; decorative choices live one level deeper. */
@Composable
fun SettingsScreen(
    recording: Boolean,
    onToggleRecording: () -> Unit,
    onSaveStop: () -> Unit,
    save: SaveState,
    sessionNotice: String?,
    sessionLimit: SessionLimit,
    onTimer: (SessionLimit) -> Unit,
    storage: StorageStatus?,
    sync: SyncState,
    pendingUploads: Int,
    onSync: () -> Unit,
    onHints: () -> Unit,
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
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }
    BackHandler(page != SettingsPage.MAIN) { page = SettingsPage.MAIN }
    when (page) {
        SettingsPage.APPEARANCE -> {
            AppearanceSettings(waveStyle, accent, sensitivity, resolvedAccent, onWaveStyle, onAccent, onSensitivity) { page = SettingsPage.MAIN }
            return
        }
        SettingsPage.TIMER -> {
            TimerSettings(sessionLimit, onTimer, sessionNotice) { page = SettingsPage.MAIN }
            return
        }
        SettingsPage.SYNC -> {
            SyncSettings(storage, sync, pendingUploads, onSync) { page = SettingsPage.MAIN }
            return
        }
        SettingsPage.MAIN -> Unit
    }

    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text("Recording") } }
            val actionFeedback = sessionNotice ?: if (save !is SaveState.Saved) saveMessage(save, 0) else null
            if (actionFeedback != null) item {
                Text(actionFeedback, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            item {
                Button(onClick = onToggleRecording, modifier = Modifier.fillMaxWidth()) {
                    Text(if (recording) "Stop listening" else "Start listening")
                }
            }
            if (recording) item {
                Button(onClick = onSaveStop, modifier = Modifier.fillMaxWidth()) { Text("Save & stop") }
            }
            item {
                Button(onClick = { page = SettingsPage.SYNC }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pendingUploads > 0) "Sync · $pendingUploads pending" else "Sync & storage")
                }
            }
            item {
                Button(onClick = { page = SettingsPage.TIMER }, modifier = Modifier.fillMaxWidth()) { Text("Timer · ${sessionLimit.label}") }
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
                Button(onClick = { page = SettingsPage.APPEARANCE }, modifier = Modifier.fillMaxWidth()) {
                    Text("Appearance")
                }
            }
            item { Button(onClick = onHints, modifier = Modifier.fillMaxWidth()) { Text("Show gesture hints") } }
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
