package earth.diego.hindsight.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.data.WaveStyle
import kotlin.math.max
import kotlin.math.min

/** Samples across the face. Odd count keeps one exactly on the centre line. */
private const val SAMPLE_COUNT = 31

/**
 * The whole app, in one screen.
 *
 * There are no buttons: the wave *is* the interface. Tap saves, because that is
 * the thing you do constantly and it deserves the entire screen as a target.
 * Long-press stops, because stopping is rare and should cost deliberation. When
 * stopped the wave settles to a flat line and the same screen becomes the way
 * back — so the app only ever has one face.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaveScreen(
    state: CaptureState,
    style: WaveStyle,
    accent: Color,
    onSave: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    // A ring of recent peaks. Plain array plus a version counter rather than a
    // snapshot list: this is rewritten ~15x a second and should not allocate.
    val levels = remember { FloatArray(SAMPLE_COUNT) }
    var version by remember { mutableIntStateOf(0) }

    // Every emitted frame pushes one sample, so the wave advances at capture rate
    // rather than on a timer that knows nothing about the audio.
    LaunchedEffect(state.sampleSeq) {
        if (!state.recording) return@LaunchedEffect
        System.arraycopy(levels, 1, levels, 0, SAMPLE_COUNT - 1)
        levels[SAMPLE_COUNT - 1] = state.peakLevel
        version++
    }

    // Amplitude, not visibility: stopping decays the wave to stillness rather
    // than cutting it, so the transition reads as "settled", not "gone".
    val amplitude = remember { Animatable(0f) }
    LaunchedEffect(state.recording) {
        amplitude.animateTo(
            targetValue = if (state.recording) 1f else 0f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        )
    }

    val resting = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (state.recording) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSave()
                        } else {
                            onResume()
                        }
                    },
                    onLongClick = {
                        if (state.recording) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop()
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction = 0.78f)
                        .height(if (style == WaveStyle.PULSE) 150.dp else 96.dp)
                        .drawBehind {
                            val colour = lerpColor(resting, accent, amplitude.value)
                            when (style) {
                                WaveStyle.BARS -> drawBars(levels, version, amplitude.value, colour)
                                WaveStyle.LINE -> drawLine(levels, version, amplitude.value, colour)
                                WaveStyle.PULSE -> drawPulse(levels, version, amplitude.value, colour)
                                WaveStyle.DOTS -> drawDots(levels, version, amplitude.value, colour)
                            }
                        },
                )

                if (!state.recording && amplitude.value < 0.05f) {
                    Text(
                        "stopped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * Ends taper toward the centre so a full-width wave sits inside a round display
 * instead of being clipped by it.
 */
private fun edgeFalloff(index: Int): Float {
    val half = (SAMPLE_COUNT - 1) / 2f
    val t = (index - half) / half
    return 1f - (t * t) * 0.55f
}

private fun DrawScope.drawBars(levels: FloatArray, version: Int, amplitude: Float, color: Color) {
    version.hashCode() // read so the draw re-runs when levels mutate in place
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
        val half = max(minHalf, levels[i] * amplitude * maxHalf * edgeFalloff(i))
        drawRoundRect(
            color = color,
            topLeft = Offset(i * barWidth * 2f, centreY - half),
            size = Size(barWidth, half * 2f),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}

private fun DrawScope.drawLine(levels: FloatArray, version: Int, amplitude: Float, color: Color) {
    version.hashCode()
    val step = size.width / (SAMPLE_COUNT - 1f)
    val centreY = size.height / 2f
    val maxHalf = size.height / 2f - 2f
    val path = Path()

    for (i in 0 until SAMPLE_COUNT) {
        // Alternate the sign so the trace crosses the axis like a real waveform
        // rather than riding above it.
        val sign = if (i % 2 == 0) 1f else -1f
        val y = centreY + sign * levels[i] * amplitude * maxHalf * edgeFalloff(i)
        if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
    }
    drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
}

private fun DrawScope.drawPulse(levels: FloatArray, version: Int, amplitude: Float, color: Color) {
    version.hashCode()
    val centre = Offset(size.width / 2f, size.height / 2f)
    val maxRadius = min(size.width, size.height) / 2f - 2f

    // Three rings sampling progressively older audio, so energy visibly travels
    // outward as it ages.
    val rings = intArrayOf(SAMPLE_COUNT - 1, SAMPLE_COUNT - 6, SAMPLE_COUNT - 11)
    rings.forEachIndexed { index, sample ->
        val base = maxRadius * (0.32f + index * 0.24f)
        val radius = base + levels[sample] * amplitude * maxRadius * 0.18f
        drawCircle(
            color = color.copy(alpha = color.alpha * (1f - index * 0.28f)),
            radius = radius,
            center = centre,
            style = Stroke(width = (3f - index * 0.6f).dp.toPx()),
        )
    }
}

private fun DrawScope.drawDots(levels: FloatArray, version: Int, amplitude: Float, color: Color) {
    version.hashCode()
    val step = size.width / (SAMPLE_COUNT - 1f)
    val centreY = size.height / 2f
    val maxRadius = step * 0.85f
    val minRadius = step * 0.14f

    for (i in 0 until SAMPLE_COUNT) {
        val radius = minRadius + levels[i] * amplitude * (maxRadius - minRadius) * edgeFalloff(i)
        drawCircle(color = color, radius = radius, center = Offset(i * step, centreY))
    }
}

private fun lerpColor(from: Color, to: Color, t: Float) = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha + (to.alpha - from.alpha) * t,
)
