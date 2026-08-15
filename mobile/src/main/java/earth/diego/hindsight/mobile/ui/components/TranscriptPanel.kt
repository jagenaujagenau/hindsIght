package earth.diego.hindsight.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What the player knows about this clip's transcript right now. */
sealed interface TranscriptState {
    data object None : TranscriptState
    data class Running(val done: Int, val total: Int) : TranscriptState
    data class Ready(val text: String) : TranscriptState
    data class Failed(val reason: String) : TranscriptState
}

/**
 * The transcript, presented as a search aid rather than a record.
 *
 * Measured recall on this recogniser is partial — different runs over the same
 * audio surface different passages — so the wording here promises "some of what
 * was said" and never implies completeness.
 */
@Composable
fun TranscriptPanel(
    state: TranscriptState,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                TranscriptState.None -> {
                    Text("Transcript", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Search what was said. Runs on your phone — nothing is uploaded — " +
                            "and takes roughly as long as the recording itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onTranscribe) { Text("Transcribe") }
                }

                is TranscriptState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            if (state.total > 0) {
                                "Transcribing ${state.done} of ${state.total}"
                            } else {
                                "Transcribing…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.total > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { state.done.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }

                is TranscriptState.Ready -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Transcript", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "partial",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(state.text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Speech recognition misses things. Trust the audio, not this.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onTranscribe) { Text("Try again") }
                }

                is TranscriptState.Failed -> {
                    Text("Transcript", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Start,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onTranscribe) { Text("Try again") }
                }
            }
        }
    }
}
