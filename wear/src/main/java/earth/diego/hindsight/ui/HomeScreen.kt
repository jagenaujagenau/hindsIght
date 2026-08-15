package earth.diego.hindsight.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SegmentedCircularProgressIndicator
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.curvedText
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.data.Retention
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.SaveOutcome

@Composable
fun HomeScreen(
    state: CaptureState,
    retention: Retention,
    pendingUploads: Int,
    micGranted: Boolean,
    onToggleRecording: () -> Unit,
    onSave: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var confirmation by remember { mutableStateOf<SaveOutcome?>(null) }

    LaunchedEffect(Unit) {
        RecorderBus.saves.collect { outcome ->
            confirmation = outcome
            // The screen is often already dark when this fires, so the wrist tap
            // is the real confirmation; the dialog is for when you are looking.
            haptics.performHapticFeedback(
                if (outcome is SaveOutcome.Saved) HapticFeedbackType.Confirm else HapticFeedbackType.Reject,
            )
        }
    }

    // ScreenScaffold's `edgeButton` slot is only offered on the scrolling
    // overloads, and this screen is a fixed layout behind a full-bleed arc — so
    // the EdgeButton is placed directly in the scaffold's BoxScope instead.
    ScreenScaffold { contentPadding ->
        if (state.recording) BufferArc(state)

        Box(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                // Keep clear of the EdgeButton, which owns the bottom of the screen.
                .padding(bottom = EDGE_BUTTON_RESERVE),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = when {
                    !micGranted -> Screen.Permission
                    state.recording -> Screen.Recording
                    else -> Screen.Idle
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "home-state",
            ) { screen ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (screen) {
                        Screen.Permission -> Text(
                            "Hindsight needs the microphone to listen",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )

                        Screen.Idle -> IdleContent(retention, pendingUploads, onOpenSettings)

                        Screen.Recording -> RecordingContent(state, retention, onOpenSettings)
                    }
                }
            }
        }

        // The one action worth a thumb: it hugs the bezel, so it is reachable
        // without aiming. Save is the hero the moment we are listening.
        when {
            !micGranted -> EdgeButton(
                onClick = onRequestPermission,
                buttonSize = EdgeButtonSize.Small,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { Text("Allow mic") }

            state.recording -> EdgeButton(
                onClick = onSave,
                buttonSize = EdgeButtonSize.Small,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { Text("Save") }

            else -> EdgeButton(
                onClick = onToggleRecording,
                buttonSize = EdgeButtonSize.Small,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { Text("Listen") }
        }
    }

    SaveConfirmation(confirmation) { confirmation = null }
}

private enum class Screen { Permission, Idle, Recording }

/** EdgeButtonSize.Small plus its spacing, reserved out of the content area. */
private val EDGE_BUTTON_RESERVE = 62.dp

/**
 * One segment per minute of retention, so the ring reads as a clock face of how
 * far back you can reach — and a glance tells you whether it is full.
 */
@Composable
private fun BufferArc(state: CaptureState) {
    val target = if (state.retentionMs > 0) {
        (state.bufferedMs.toFloat() / state.retentionMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val progress by animateFloatAsState(target, label = "buffer-fill")
    val minutes = (state.retentionMs / 60_000).toInt().coerceIn(1, 12)

    SegmentedCircularProgressIndicator(
        segmentCount = minutes,
        progress = { progress },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun IdleContent(retention: Retention, pendingUploads: Int, onOpenSettings: () -> Unit) {
    Text(
        "KEEPS THE LAST",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    // A pill, not a TextButton: Wear's TextButton is icon-sized and would clip this.
    Button(
        onClick = onOpenSettings,
        colors = ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier.size(width = 116.dp, height = 46.dp),
    ) {
        Text(
            retention.label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    if (pendingUploads > 0) {
        Text(
            "$pendingUploads waiting for phone",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingContent(
    state: CaptureState,
    retention: Retention,
    onOpenSettings: () -> Unit,
) {
    // A 192 dp screen cannot hold a level meter, a readout, two chips and a hero
    // button without cramping all of them. Stop and retention move to the options
    // screen, reached by tapping the readout, so Save keeps the whole stage.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onOpenSettings),
    ) {
        LevelMeter(state.peakLevel)
        Spacer(Modifier.height(6.dp))
        Text(
            formatDuration(state.bufferedMs),
            style = MaterialTheme.typography.displayMedium,
        )
        Text(
            "of ${retention.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "tap for options",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** Five bars off the encoder's peak — the only proof the mic is live once the screen dims. */
@Composable
private fun LevelMeter(peak: Float) {
    val lit = (peak * 6f).toInt().coerceIn(0, 5)
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        repeat(5) { index ->
            Box(
                Modifier
                    .width(5.dp)
                    .height((6 + index * 3).dp)
                    .background(if (index < lit) active else idle, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SaveConfirmation(outcome: SaveOutcome?, onDismiss: () -> Unit) {
    if (outcome == null) return

    val message = when (outcome) {
        is SaveOutcome.Saved -> "Saved ${formatDuration(outcome.durationMs)}"
        SaveOutcome.NothingBuffered -> "Nothing buffered yet"
        is SaveOutcome.Failed -> outcome.message
    }
    // Resolved here because the style is itself composable; the curved lambda is not.
    val style = ConfirmationDialogDefaults.curvedTextStyle

    SuccessConfirmationDialog(
        visible = true,
        onDismissRequest = onDismiss,
        curvedText = { curvedText(text = message, style = style) },
    )
}

internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
