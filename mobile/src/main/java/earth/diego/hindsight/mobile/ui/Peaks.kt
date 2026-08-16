package earth.diego.hindsight.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import earth.diego.hindsight.mobile.audio.Waveform
import earth.diego.hindsight.mobile.data.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Amplitude envelopes, kept in memory once read.
 *
 * Every row in the library draws its own waveform, so these are read constantly
 * while scrolling. They are ~2 KB each, so holding them is far cheaper than
 * touching the disk on every recomposition.
 */
private val cache = ConcurrentHashMap<String, FloatArray>()

@Composable
fun rememberPeaks(clip: Clip): FloatArray? {
    var peaks by remember(clip.id) { mutableStateOf(cache[clip.id]) }

    LaunchedEffect(clip.id, clip.hasWaveform) {
        if (peaks != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            Waveform.read(clip.file)?.let(Waveform::normalised)
        }
        if (loaded != null) {
            cache[clip.id] = loaded
            peaks = loaded
        }
    }
    return peaks
}

/** Forgets a clip's envelope, for when it is deleted. */
fun forgetPeaks(clipId: String) {
    cache.remove(clipId)
}
