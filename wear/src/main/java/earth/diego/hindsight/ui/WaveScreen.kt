package earth.diego.hindsight.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.data.Sensitivity
import earth.diego.hindsight.data.WaveStyle
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.SaveState
import earth.diego.hindsight.sync.SyncState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Samples across the face. Odd count keeps one exactly on the centre line. */
private const val SAMPLE_COUNT = 31

/** The entire face remains a save target, with explicit status and action semantics. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaveScreen(
    state: CaptureState,
    save: SaveState,
    pendingUploads: Int,
    visible: Boolean,
    style: WaveStyle,
    accent: Color,
    sensitivity: Sensitivity,
    onSave: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    showHints: Boolean = true,
    sync: SyncState = SyncState.Idle,
    warning: String? = null,
    sessionNotice: String? = null,
) {
    val status = when {
        state.error != null -> "Recording interrupted"
        state.starting -> "Starting…"
        state.recording -> "Listening"
        else -> "Stopped"
    }
    val action = when {
        state.starting -> "Starting…"
        save == SaveState.Saving -> "Saving…"
        state.recording -> "Tap to save"
        state.error != null -> "Tap to retry"
        else -> "Tap to start"
    }
    val confirmationFlow = remember(save, visible) {
        flow {
            val remaining = if (save is SaveState.Saved && visible) confirmationRemainingMs(save, System.currentTimeMillis()) else 0L
            emit(remaining > 0)
            if (remaining > 0) { delay(remaining); emit(false) }
        }
    }
    val recentConfirmation by confirmationFlow.collectAsStateWithLifecycle(false, minActiveState = Lifecycle.State.RESUMED)
    val displayedSave = if (save is SaveState.Saved && !recentConfirmation) SaveState.Idle else save
    val feedback = state.error ?: sessionNotice ?: saveMessage(displayedSave, 0) ?: warning
    val pendingMessage = syncMessage(sync, pendingUploads)
    val enlargedText = LocalDensity.current.fontScale > 1.1f
    val scrollState = rememberScrollState()

    ScreenScaffold {
        Box(
            Modifier.fillMaxSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !state.starting && save != SaveState.Saving,
                    role = Role.Button,
                    onClickLabel = when {
                        state.recording -> "Save buffered audio"
                        state.error != null -> "Retry recording"
                        else -> "Start listening"
                    },
                    onLongClickLabel = if (state.recording) "Stop listening" else null,
                    onClick = { if (state.recording) onSave() else onResume() },
                    onLongClick = if (state.recording) onStop else null,
                )
                .semantics { stateDescription = status },
            contentAlignment = Alignment.Center,
        ) {
            // Reserve the round display's top/bottom chords for clock and pager.
            // The graphic yields space to text on small watches / larger fonts.
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 28.dp)
                    .then(if (enlargedText) Modifier.verticalScroll(scrollState) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(status, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                if (state.recording) {
                    Text(
                        "${formatBufferDuration(state.bufferedMs)} / ${formatBufferDuration(state.retentionMs)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.sessionRemainingMs?.let { remaining ->
                    Text("Timer · ${formatBufferDuration(remaining)}", style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                }
                WaveGraphic(
                    recording = state.recording,
                    visible = visible,
                    style = style,
                    accent = accent,
                    sensitivity = sensitivity,
                    modifier = if (enlargedText) Modifier.fillMaxWidth().height(48.dp)
                        else Modifier.weight(1f).fillMaxWidth().heightIn(max = 96.dp),
                )
                if (showHints || !state.recording || save == SaveState.Saving) {
                    Text(action, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
                val footer = feedback ?: if (showHints && state.recording) "Hold to stop · swipe for settings"
                    else if (showHints) "Swipe for settings" else null
                if (footer != null) Text(
                    footer,
                    modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.error != null || save is SaveState.Failed || warning != null)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (pendingMessage != null) Text(pendingMessage,
                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaveGraphic(
    recording: Boolean,
    visible: Boolean,
    style: WaveStyle,
    accent: Color,
    sensitivity: Sensitivity,
    modifier: Modifier,
) {
    val motion = remember { WaveformMotion(SAMPLE_COUNT) }
    val currentRecording by rememberUpdatedState(recording)
    var version by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Audio changes targets at ~15 Hz; one frame-clock loop interpolates only
    // while something is moving. Both jobs stop off-screen or outside RESUMED.
    // Snapshot writes invalidate drawing, not composition or layout.
    LaunchedEffect(visible, sensitivity, lifecycle) {
        if (!visible) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            motion.reset(currentRecording)
            version++
            val changes = Channel<Unit>(Channel.CONFLATED)
            launch {
                snapshotFlow { currentRecording }.collectLatest { listening ->
                    motion.setRecording(listening)
                    changes.trySend(Unit)
                    if (listening) RecorderBus.levels.collect { peak ->
                        motion.push(perceptualLevel(peak, sensitivity.rangeDb))
                        if (motion.needsFrame) changes.trySend(Unit)
                    }
                }
            }
            for (change in changes) {
                var previousFrame = 0L
                while (motion.needsFrame) {
                    val scale = durationScale?.scaleFactor ?: 1f
                    if (scale <= 0f) {
                        if (motion.advance(0f, durationScale = 0f)) version++
                        break
                    }
                    withFrameNanos { frame ->
                        val delta = if (previousFrame == 0L) 1f / 60f
                            else (frame - previousFrame) / 1_000_000_000f
                        previousFrame = frame
                        if (motion.advance(delta, scale)) version++
                    }
                }
            }
        }
    }
    val resting = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val path = remember { Path() }
    val density = LocalDensity.current
    val strokes = remember(density) { Array(3) { i -> Stroke(with(density) { (3f - i * 0.6f).dp.toPx() }) } }
    Box(modifier.drawBehind {
        if (size.width <= 4f || size.height <= 4f) return@drawBehind
        @Suppress("UNUSED_EXPRESSION")
        version // Snapshot read: the array mutates in place, so invalidate drawing explicitly.
        val colour = lerpColor(resting, accent, motion.activity)
        // Levels already decay to zero on stop; avoid applying a second envelope
        // that would make the wave collapse abruptly while its colour is fading.
        when (style) {
            WaveStyle.BARS -> drawBars(motion.levels, colour)
            WaveStyle.LINE -> drawLine(motion.levels, colour, path, strokes[0])
            WaveStyle.PULSE -> drawPulse(motion.levels, colour, strokes)
            WaveStyle.DOTS -> drawDots(motion.levels, colour)
        }
    })
}

/**
 * Maps a raw sample peak onto bar height, logarithmically, across a window chosen
 * by [Sensitivity].
 *
 * Linear looks broken: measured on a real watch, normal speech peaks around 4-8%
 * of full scale, which draws as a flat line even though the recording is fine.
 * Metering in dBFS the way audio equipment does puts conversation near 0.6 with
 * headroom left for anything louder. There is deliberately no auto-gain — it
 * pinned everything to full height and the wave stopped meaning anything.
 *
 * Meter samples stay the honest raw value; this is presentation only.
 */
private fun perceptualLevel(peak: Float, rangeDb: Float): Float {
    if (peak <= SILENCE_FLOOR) return 0f
    val db = 20f * log10(peak)
    val level = ((db + rangeDb) / rangeDb).coerceIn(0f, 1f)
    // Gate the room's noise floor so silence draws a still line.
    return if (level < NOISE_GATE) 0f else level
}

/** Below this the room is silent enough that the line should be still. */
private const val SILENCE_FLOOR = 0.0005f

/** Below this the signal is room tone, not something worth drawing. */
private const val NOISE_GATE = 0.12f


/**
 * Ends taper toward the centre so a full-width wave sits inside a round display
 * instead of being clipped by it.
 */
private fun edgeFalloff(index: Int): Float {
    val half = (SAMPLE_COUNT - 1) / 2f
    val t = (index - half) / half
    return 1f - (t * t) * 0.35f
}

private fun DrawScope.drawBars(levels: FloatArray, color: Color) {
    val barWidth = size.width / (SAMPLE_COUNT * 2f - 1f)
    val centreY = size.height / 2f
    val maxHalf = size.height / 2f
    val minHalf = barWidth / 2f

    // Continuous baseline, so silence reads as one clean line rather than dots
    // and the bars appear to rise out of it.
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, centreY - minHalf),
        size = Size(size.width, minHalf * 2f),
        cornerRadius = CornerRadius(minHalf),
    )

    for (i in 0 until SAMPLE_COUNT) {
        val half = max(minHalf, levels[i] * maxHalf * edgeFalloff(i))
        drawRoundRect(
            color = color,
            topLeft = Offset(i * barWidth * 2f, centreY - half),
            size = Size(barWidth, half * 2f),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}

private fun DrawScope.drawLine(levels: FloatArray, color: Color, path: Path, stroke: Stroke) {
    val inset = stroke.width / 2f
    val step = (size.width - stroke.width).coerceAtLeast(0f) / (SAMPLE_COUNT - 1f)
    val centreY = size.height / 2f
    val maxHalf = (centreY - inset).coerceAtLeast(0f)
    path.reset()
    var previousY = centreY

    for (i in 0 until SAMPLE_COUNT) {
        // A stylised amplitude trace, not raw PCM. Horizontal tangents make
        // each crest rounded, with control points bounded by adjacent samples.
        val sign = if (i % 2 == 0) 1f else -1f
        val x = inset + i * step
        val y = centreY + sign * levels[i] * maxHalf * edgeFalloff(i)
        if (i == 0) path.moveTo(x, y) else {
            val middleX = x - step / 2f
            path.cubicTo(middleX, previousY, middleX, y, x, y)
        }
        previousY = y
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawPulse(levels: FloatArray, color: Color, strokes: Array<Stroke>) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val maxRadius = (min(size.width, size.height) / 2f - strokes[0].width / 2f).coerceAtLeast(0f)

    // Three rings sampling progressively older audio, so energy visibly travels
    // outward as it ages.
    for (index in 0..2) {
        val sample = SAMPLE_COUNT - 1 - index * 5
        val energy = levels[sample]
        val base = maxRadius * (0.28f + index * 0.24f)
        val radius = base + energy * maxRadius * 0.16f
        drawCircle(
            color = color.copy(alpha = color.alpha * (1f - index * 0.22f) * (0.55f + energy * 0.45f)),
            radius = radius,
            center = centre,
            style = strokes[index],
        )
    }
}

private fun DrawScope.drawDots(levels: FloatArray, color: Color) {
    // Leave room for the outer circles and a small gap at maximum energy.
    val step = size.width / (SAMPLE_COUNT - 1f + 0.88f)
    val centreY = size.height / 2f
    val maxRadius = min(step * 0.44f, centreY)
    val minRadius = min(step * 0.14f, maxRadius)

    for (i in 0 until SAMPLE_COUNT) {
        val radius = minRadius + levels[i] * (maxRadius - minRadius) * edgeFalloff(i)
        drawCircle(color = color, radius = radius, center = Offset(maxRadius + i * step, centreY))
    }
}

private fun lerpColor(from: Color, to: Color, t: Float) = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha + (to.alpha - from.alpha) * t,
)
