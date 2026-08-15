package earth.diego.hindsight.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.diego.hindsight.mobile.data.Clip
import earth.diego.hindsight.mobile.data.ClipStore
import earth.diego.hindsight.mobile.player.PlaybackState
import earth.diego.hindsight.mobile.ui.components.WaveformView

private val SPEEDS = listOf(0.75f, 1f, 1.5f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    clip: Clip,
    peaks: FloatArray?,
    playback: PlaybackState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSkip: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(clip.displayTitle, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Rename")
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            // The bar already shows the time; give the date here instead of
            // repeating it.
            Text(
                ClipStore.fullDate(clip.recordedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            WaveformView(
                peaks = peaks,
                progress = playback.progress,
                onSeek = onSeekFraction,
                height = 180.dp,
            )

            if (peaks == null) {
                Text(
                    "Building waveform…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    ClipStore.formatPosition(playback.positionMs),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    ClipStore.formatPosition(playback.durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                IconButton(onClick = { onSkip(-10_000) }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Filled.Replay10,
                        contentDescription = "Back 10 seconds",
                        modifier = Modifier.size(32.dp),
                    )
                }
                FilledIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.playing) "Pause" else "Play",
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(onClick = { onSkip(10_000) }, modifier = Modifier.size(56.dp)) {
                    Icon(
                        Icons.Filled.Forward10,
                        contentDescription = "Forward 10 seconds",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = playback.speed == speed,
                        onClick = { onSpeed(speed) },
                        label = { Text(if (speed == 1f) "1×" else "${speed}×") },
                    )
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }

    if (renaming) {
        RenameDialog(
            initial = clip.title.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = {
                onRename(it)
                renaming = false
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this clip?") },
            // These recordings cannot be recreated, so the destructive path is
            // confirmed here and still undoable from the snackbar afterwards.
            text = { Text("It will move to the trash. You can undo straight away.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this clip") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Kitchen conversation") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
