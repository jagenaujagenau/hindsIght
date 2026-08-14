package com.warmly.watchrecorder.mobile.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PlaybackState(
    val playingId: String? = null,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

/**
 * One clip at a time, backed by [MediaPlayer] — these are short mono AAC files
 * from a single local source, so ExoPlayer's adaptive machinery would be dead weight.
 */
class ClipPlayer {

    private companion object { const val TAG = "ClipPlayer" }

    private var player: MediaPlayer? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun toggle(id: String, file: File) {
        if (_state.value.playingId == id) {
            val active = player
            if (active != null && active.isPlaying) active.pause() else active?.start()
            return
        }
        play(id, file)
    }

    private fun play(id: String, file: File) {
        stop()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            _state.value = PlaybackState(id, 0, player?.duration ?: 0)
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot play ${file.name}", t)
            stop()
        }
    }

    /** Called from a UI-side ticker; keeps the progress bar honest without a listener. */
    fun syncPosition() {
        val active = player ?: return
        runCatching {
            _state.value = _state.value.copy(positionMs = active.currentPosition)
        }
    }

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun stop() {
        player?.runCatching {
            reset()
            release()
        }
        player = null
        _state.value = PlaybackState()
    }
}
