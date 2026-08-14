package earth.diego.hindsight.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.SaveOutcome
import kotlinx.coroutines.delay

@Composable
fun RecorderScreen(
    state: CaptureState,
    retention: Retention,
    pendingUploads: Int,
    micGranted: Boolean,
    onToggleRecording: () -> Unit,
    onSave: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    var banner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        RecorderBus.saves.collect { outcome ->
            banner = when (outcome) {
                is SaveOutcome.Saved ->
                    "Saved ${formatDuration(outcome.durationMs)}\n${outcome.sizeBytes / 1024} KB"
                SaveOutcome.NothingBuffered -> "Nothing buffered yet"
                is SaveOutcome.Failed -> outcome.message
            }
        }
    }

    // Auto-dismiss so the banner never sits between the user and a second save.
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(2_500)
            banner = null
        }
    }

    val fill = if (state.retentionMs > 0) {
        (state.bufferedMs.toFloat() / state.retentionMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedFill by animateFloatAsState(fill, label = "buffer-fill")

    Scaffold(timeText = { TimeText() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

            if (state.recording) {
                CircularProgressIndicator(
                    progress = animatedFill,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    startAngle = 290f,
                    endAngle = 250f,
                    strokeWidth = 5.dp,
                    indicatorColor = MaterialTheme.colors.primary,
                    trackColor = MaterialTheme.colors.onSurface.copy(alpha = 0.15f),
                )
            }

            // Inset the centring region rather than the content, so the column is
            // centred in the space *below* TimeText instead of overlapping it.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 26.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 28.dp),
                ) {
                    when {
                        !micGranted -> PermissionPrompt(onRequestPermission)

                        !state.recording ->
                            IdleControls(retention, pendingUploads, onToggleRecording, onOpenSettings)

                        else -> RecordingControls(
                            state = state,
                            retention = retention,
                            banner = banner,
                            onSave = onSave,
                            onStop = onToggleRecording,
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Text(
        text = "Microphone access needed",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.body2,
    )
    Spacer(Modifier.height(10.dp))
    Button(onClick = onRequestPermission, modifier = Modifier.size(52.dp)) {
        Text("Grant", style = MaterialTheme.typography.caption1)
    }
}

@Composable
private fun IdleControls(
    retention: Retention,
    pendingUploads: Int,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Text(
        "Keeps last",
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onSurfaceVariant,
    )
    Text(retention.label, style = MaterialTheme.typography.title2)
    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onStart,
        modifier = Modifier.size(58.dp),
        colors = ButtonDefaults.primaryButtonColors(),
    ) {
        Text("REC", style = MaterialTheme.typography.button)
    }

    Spacer(Modifier.height(10.dp))
    CompactButton(
        onClick = onOpenSettings,
        colors = ButtonDefaults.secondaryButtonColors(),
    ) {
        Text("···", style = MaterialTheme.typography.caption1)
    }

    if (pendingUploads > 0) {
        Spacer(Modifier.height(6.dp))
        Text(
            "$pendingUploads clip${if (pendingUploads == 1) "" else "s"} awaiting phone",
            style = MaterialTheme.typography.caption3,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingControls(
    state: CaptureState,
    retention: Retention,
    banner: String?,
    onSave: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (banner != null) {
        Text(
            banner,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary,
        )
        Spacer(Modifier.height(8.dp))
    } else {
        LevelMeter(state.peakLevel)
        Spacer(Modifier.height(6.dp))
        Text(formatDuration(state.bufferedMs), style = MaterialTheme.typography.title3)
        Text(
            "of ${retention.label} buffered",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = onSave,
        modifier = Modifier.size(62.dp),
        colors = ButtonDefaults.primaryButtonColors(),
    ) {
        Text("SAVE", style = MaterialTheme.typography.button)
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactButton(onClick = onStop, colors = ButtonDefaults.secondaryButtonColors()) {
            Text("■", style = MaterialTheme.typography.caption1)
        }
        CompactButton(onClick = onOpenSettings, colors = ButtonDefaults.secondaryButtonColors()) {
            Text("···", style = MaterialTheme.typography.caption1)
        }
    }
}

/**
 * Five bars driven by the encoder's peak sample. This is the only confirmation the
 * user gets that the mic is actually live once the screen dims, so it is worth the
 * ~4 Hz recomposition of five boxes and nothing else.
 */
@Composable
private fun LevelMeter(peak: Float) {
    val lit = (peak * 6f).toInt().coerceIn(0, 5)
    val active = MaterialTheme.colors.primary
    val idle = MaterialTheme.colors.onSurface.copy(alpha = 0.25f)

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(5) { index ->
            Box(
                Modifier
                    .width(5.dp)
                    .height((6 + index * 3).dp)
                    .background(
                        color = if (index < lit) active else idle,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
