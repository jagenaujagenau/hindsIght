package earth.diego.hindsight.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.diego.hindsight.mobile.data.Clip
import earth.diego.hindsight.mobile.data.ClipStore
import earth.diego.hindsight.mobile.player.ClipPlayer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipListScreen(player: ClipPlayer) {
    val context = LocalContext.current
    val clips by ClipStore.clips.collectAsStateWithLifecycle()
    val playback by player.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { ClipStore.refresh(context) }

    // Poll only while something is actually playing.
    LaunchedEffect(playback.playingId) {
        while (playback.playingId != null) {
            player.syncPosition()
            delay(200)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Watch clips") }) },
    ) { padding ->
        if (clips.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing yet.\nPress SAVE on your watch and the clip will land here.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipCard(
                        clip = clip,
                        isActive = playback.playingId == clip.id,
                        progress = if (playback.playingId == clip.id && playback.durationMs > 0) {
                            playback.positionMs.toFloat() / playback.durationMs
                        } else {
                            0f
                        },
                        isPlaying = player.isPlaying,
                        onToggle = { player.toggle(clip.id, clip.file) },
                        onDelete = {
                            if (playback.playingId == clip.id) player.stop()
                            ClipStore.delete(context, clip)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    clip: Clip,
    isActive: Boolean,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                ClipStore.formatTimestamp(clip.receivedAt),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${ClipStore.formatDuration(clip.durationSeconds)} · ${clip.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isActive) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onToggle) {
                    Text(if (isActive && isPlaying) "Pause" else "Play")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
