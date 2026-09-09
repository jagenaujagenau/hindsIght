package earth.diego.hindsight.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import earth.diego.hindsight.shared.AudioSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/** What the UI needs to draw, and nothing more. */
data class CaptureState(
    val recording: Boolean = false,
    val bufferedMs: Long = 0,
    val retentionMs: Long = 0,
    val starting: Boolean = false,
    val error: String? = null,
)

/**
 * Continuous 16 kHz mono AAC capture into a rolling on-disk window.
 *
 * The whole pipeline lives on one thread driven by blocking `AudioRecord.read`,
 * which is what paces the loop — there is no timer, no polling and no second
 * thread to synchronise with. Commands from other threads are drained from a
 * queue between iterations (normally one 64 ms frame). Lifecycle changes are
 * serialized, and blocking thread joins happen on IO rather than the UI thread.
 */
class RingRecorder internal constructor(
    bufferDir: File,
    private val threadFactory: (Runnable) -> Thread = { Thread(it, "ring-recorder") },
) {

    private companion object {
        const val TAG = "RingRecorder"

        /**
         * Exactly one AAC frame of PCM per read (1024 samples, 16-bit mono).
         *
         * This must stay 1:1 with encoded frames. Reading two frames' worth while
         * emitting state per encoded frame meant `peak` was measured once but
         * consumed twice, so every second frame reported a level of zero and the
         * waveform was half blank — which reads as "the mic is barely working"
         * even though the recording itself is fine.
         */
        const val PCM_READ_BYTES = AudioSpec.SAMPLES_PER_FRAME * 2

        /** ~2.7 s of encoded audio in flight; caps loss if the service is killed. */
        const val SEGMENT_WRITE_BUFFER = 8 * 1024

        /** Status text only needs roughly one update per second. */
        const val STATE_EMIT_INTERVAL_FRAMES = 16
    }

    private val ring = SegmentRing(bufferDir)
    private class Command(val run: () -> Unit, val cancel: () -> Unit)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val commandLock = Any()
    private val lifecycle = Mutex()

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _levels = MutableSharedFlow<Float>(
        extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val levels = _levels.asSharedFlow()
    @Volatile private var meteringEnabled = false

    fun setMeteringEnabled(enabled: Boolean) { meteringEnabled = enabled }

    @Volatile private var running = false
    private var thread: Thread? = null

    // --- recorder-thread state, never touched from outside ---
    private var retentionFrames = AudioSpec.framesForMinutes(5)
    private var segmentIndex = 0L
    private var currentFile: File? = null
    private var currentStream: OutputStream? = null
    private var currentFrames = 0
    private var framesSinceEmit = 0
    private var peak = 0f

    suspend fun start(retentionMinutes: Int) = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            if (running) {
                setRetention(retentionMinutes)
            } else {
                // Never overlap a new capture with an old thread still releasing resources.
                thread?.join()
                retentionFrames = AudioSpec.framesForMinutes(retentionMinutes)
                framesSinceEmit = 0
                peak = 0f
                _state.value = CaptureState(starting = true)
                synchronized(commandLock) { running = true }
                thread = threadFactory(Runnable { runLoop() }).apply { start() }
            }
        }
    }

    suspend fun stop() = lifecycle.withLock {
        withContext(Dispatchers.IO) {
            synchronized(commandLock) { running = false }
            thread?.join()
            thread = null
            _state.value = CaptureState()
        }
    }

    fun setRetention(minutes: Int) = post {
        retentionFrames = AudioSpec.framesForMinutes(minutes)
        ring.setRetentionFrames(retentionFrames)
    }

    /**
     * Closes the in-flight segment and hands back the newest [minutes] of audio,
     * pinned against eviction. Callers **must** [release] the result.
     */
    suspend fun pinNewest(minutes: Int): PinnedWindow? {
        if (!running) return null
        val result = CompletableDeferred<PinnedWindow?>()
        post(onStopped = { result.complete(null) }) {
            rollSegment()
            val frames = AudioSpec.framesForMinutes(minutes).coerceAtMost(ring.bufferedFrames)
            if (frames <= 0) {
                result.complete(null)
            } else {
                result.complete(PinnedWindow(ring.pinNewest(frames), frames))
            }
        }
        return result.await()
    }

    suspend fun release(window: PinnedWindow) = lifecycle.withLock {
        val released = CompletableDeferred<Boolean>()
        post(onStopped = { released.complete(false) }) {
            ring.unpin(window.segments)
            released.complete(true)
        }
        if (!released.await()) withContext(Dispatchers.IO) {
            thread?.join()
            ring.unpin(window.segments)
        }
    }

    private fun post(onStopped: () -> Unit = {}, block: () -> Unit) {
        synchronized(commandLock) {
            if (running) commands += Command(block, onStopped) else onStopped()
        }
    }

    // ------------------------------------------------------------------
    // Recorder thread
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller holds RECORD_AUDIO; service refuses to start otherwise
    private fun runLoop() {
        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        var failure: String? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val minBuffer = AudioRecord.getMinBufferSize(
                AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "AudioRecord unavailable (min buffer $minBuffer)" }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, PCM_READ_BYTES) * 4,
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.apply {
                configure(
                    MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        AudioSpec.SAMPLE_RATE,
                        AudioSpec.CHANNEL_COUNT,
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.BIT_RATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_READ_BYTES)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                start()
            }

            ring.setRetentionFrames(retentionFrames)
            openSegment()
            audioRecord.startRecording()
            _state.value = CaptureState(recording = true, retentionMs = ClipBuilder.durationMs(retentionFrames))

            val info = MediaCodec.BufferInfo()
            val adtsHeader = ByteArray(Adts.headerSize())
            var payload = ByteArray(4096)
            var samplesFed = 0L

            while (running) {
                drainCommands()

                val inIndex = codec.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    val input = codec.getInputBuffer(inIndex)!!
                    input.clear()
                    val want = minOf(input.capacity(), PCM_READ_BYTES)
                    val read = audioRecord.read(input, want, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        if (meteringEnabled) peak = maxOf(peak, peakOf(input, read))
                        val ptsUs = samplesFed * 1_000_000L / AudioSpec.SAMPLE_RATE
                        codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                        samplesFed += read / 2
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        if (read < 0) throw IllegalStateException("AudioRecord.read failed: $read")
                    }
                }

                payload = drainEncoder(codec, info, adtsHeader, payload)
            }
        } catch (t: Exception) {
            Log.e(TAG, "Capture loop stopped", t)
            failure = if (t is IOException) "Storage unavailable. Free space, then tap to retry."
                else "Microphone unavailable. Check access, then tap to retry."
        } finally {
            synchronized(commandLock) {
                running = false
                while (true) (commands.poll() ?: break).cancel()
            }
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { rollSegment() }
            runCatching { ring.clear() }
            currentStream = null
            currentFile = null
            // Failure is terminal and published only after the old engine is fully released.
            _state.value = CaptureState(error = failure)
        }
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            try {
                command.run()
            } catch (t: Exception) {
                command.cancel()
                throw t
            }
        }
    }

    private fun drainEncoder(
        codec: MediaCodec, info: MediaCodec.BufferInfo, adtsHeader: ByteArray, reusablePayload: ByteArray,
    ): ByteArray {
        var payload = reusablePayload
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) return payload

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val output = codec.getOutputBuffer(outIndex)!!
                Adts.writeHeader(adtsHeader, info.size)
                val stream = currentStream
                if (stream != null) {
                    stream.write(adtsHeader)
                    if (payload.size < info.size) payload = ByteArray(info.size)
                    output.position(info.offset)
                    output.get(payload, 0, info.size)
                    stream.write(payload, 0, info.size)
                    onFrameWritten()
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun onFrameWritten() {
        currentFrames++
        if (currentFrames >= AudioSpec.FRAMES_PER_SEGMENT) rollSegment()

        if (meteringEnabled) _levels.tryEmit(peak)
        peak = 0f
        if (++framesSinceEmit >= STATE_EMIT_INTERVAL_FRAMES) {
            framesSinceEmit = 0
            val buffered = (ring.bufferedFrames + currentFrames)
                .coerceAtMost(retentionFrames)
            _state.value = CaptureState(
                recording = true,
                bufferedMs = ClipBuilder.durationMs(buffered),
                retentionMs = ClipBuilder.durationMs(retentionFrames),
            )
        }
    }

    private fun openSegment() {
        val file = ring.nextSegmentFile(segmentIndex++)
        currentFile = file
        currentStream = BufferedOutputStream(file.outputStream(), SEGMENT_WRITE_BUFFER)
        currentFrames = 0
    }

    /** Closes the in-flight segment into the ring and opens a fresh one. */
    private fun rollSegment() {
        val stream = currentStream ?: return
        val file = currentFile ?: return
        currentStream = null
        try {
            stream.close() // Flush failures are capture failures, not valid segments.
        } catch (t: Exception) {
            file.delete()
            currentFrames = 0
            throw t
        }
        if (currentFrames > 0) ring.add(Segment(file, currentFrames)) else file.delete()
        currentFrames = 0
        if (running) openSegment()
    }

    private fun peakOf(buffer: java.nio.ByteBuffer, byteCount: Int): Float {
        var max = 0
        // One sample in eight is plenty for a level meter and keeps this off the profiler.
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((buffer.get(i + 1).toInt() shl 8) or (buffer.get(i).toInt() and 0xFF)).toShort()
            val magnitude = abs(sample.toInt())
            if (magnitude > max) max = magnitude
            i += 16
        }
        return max / 32768f
    }
}

/** A pinned, immutable view of the ring that is safe to read off-thread. */
class PinnedWindow(
    val segments: List<Segment>,
    val frames: Int,
)
