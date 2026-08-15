package earth.diego.hindsight.mobile.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The clip's amplitude envelope, drawn as mirrored bars, and the primary way to
 * move through a recording.
 *
 * Scrubbing is the whole point: a 60-minute window is unusable if the only way in
 * is to play from the start. Dragging seeks continuously, and a tap jumps.
 */
@Composable
fun WaveformView(
    peaks: FloatArray?,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    barCount: Int = 96,
) {
    val haptics = LocalHapticFeedback.current
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableFloatStateOf(1f) }

    val played = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val remaining = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)

    // While dragging, follow the finger rather than the player, so the waveform
    // never fights the seek that is still catching up.
    val shown = dragFraction ?: progress

    Box(
        modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(peaks) {
                    width = size.width.toFloat()
                    detectTapGestures { offset ->
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSeek((offset.x / size.width).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(peaks) {
                    width = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let(onSeek)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .drawBehind {
                    drawWaveform(peaks, barCount, shown, played, remaining)
                },
        )
    }
}

private fun DrawScope.drawWaveform(
    peaks: FloatArray?,
    barCount: Int,
    progress: Float,
    played: Color,
    remaining: Color,
) {
    val barWidth = size.width / (barCount * 1.7f)
    val step = size.width / barCount
    val centreY = size.height / 2f
    val maxHalf = size.height / 2f
    val minHalf = barWidth / 2f
    val playedBars = (progress * barCount).roundToInt()

    for (i in 0 until barCount) {
        // Take the loudest sample in this bar's slice, so short spikes survive the
        // reduction instead of being averaged into invisibility.
        val amplitude = if (peaks == null || peaks.isEmpty()) {
            0f
        } else {
            val from = (i * peaks.size / barCount).coerceIn(0, peaks.size - 1)
            val to = (((i + 1) * peaks.size) / barCount).coerceIn(from + 1, peaks.size)
            var loudest = 0f
            for (p in from until to) loudest = max(loudest, peaks[p])
            loudest
        }

        val half = max(minHalf, amplitude * maxHalf)
        drawRoundRect(
            color = if (i < playedBars) played else remaining,
            topLeft = Offset(i * step + (step - barWidth) / 2f, centreY - half),
            size = Size(barWidth, half * 2f),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}
