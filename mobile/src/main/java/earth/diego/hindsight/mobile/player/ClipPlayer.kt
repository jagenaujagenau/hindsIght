package earth.diego.hindsight.mobile.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlaybackState(
    val clipId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = 1f,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Single-clip playback over [MediaPlayer].
 *
 * These are short mono AAC files from local storage, so ExoPlayer's adaptive
 * streaming machinery would be weight without benefit. What matters here is
 * accurate seeking, which MediaPlayer does fine.
 */
class ClipPlayer {

    private companion object {
        const val TAG = "ClipPlayer"
        const val SKIP_MS = 15_000
    }

    private var player: MediaPlayer? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun open(clipId: String, file: File, autoPlay: Boolean = true) {
        if (_state.value.clipId == clipId && player != null) {
            if (autoPlay && _state.value.playing.not()) togglePlayPause()
            return
        }
        release()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    // Park at the end rather than resetting, so the waveform still
                    // shows where you got to.
                    _state.value = _state.value.copy(playing = false, positionMs = duration)
                }
                prepare()
                if (autoPlay) start()
            }
            _state.value = PlaybackState(
                clipId = clipId,
                playing = autoPlay,
                positionMs = 0,
                durationMs = player?.duration ?: 0,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot open ${file.name}", t)
            release()
        }
    }

    fun togglePlayPause() {
        val active = player ?: return
        runCatching {
            if (active.isPlaying) {
                active.pause()
                _state.value = _state.value.copy(playing = false)
            } else {
                // Restart from the beginning if we are parked at the end.
                if (active.currentPosition >= active.duration - 50) active.seekTo(0)
                active.start()
                _state.value = _state.value.copy(playing = true)
            }
        }
    }

    fun seekTo(positionMs: Int) {
        val active = player ?: return
        val target = positionMs.coerceIn(0, active.duration)
        runCatching {
            active.seekTo(target)
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun seekToFraction(fraction: Float) {
        val active = player ?: return
        seekTo((fraction.coerceIn(0f, 1f) * active.duration).toInt())
    }

    fun skip(deltaMs: Int = SKIP_MS) {
        val active = player ?: return
        seekTo(active.currentPosition + deltaMs)
    }

    fun setSpeed(speed: Float) {
        val active = player ?: return
        runCatching {
            val wasPlaying = active.isPlaying
            // Setting params starts playback as a side effect; preserve intent.
            active.playbackParams = active.playbackParams.setSpeed(speed)
            if (!wasPlaying) active.pause()
            _state.value = _state.value.copy(speed = speed, playing = wasPlaying)
        }
    }

    /** Called from a UI ticker while playing; MediaPlayer has no position callback. */
    fun syncPosition() {
        val active = player ?: return
        runCatching {
            _state.value = _state.value.copy(
                positionMs = active.currentPosition,
                playing = active.isPlaying,
            )
        }
    }

    fun release() {
        player?.runCatching {
            reset()
            release()
        }
        player = null
        _state.value = PlaybackState()
    }
}
