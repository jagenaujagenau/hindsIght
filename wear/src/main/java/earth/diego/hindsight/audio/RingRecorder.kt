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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/** What the UI needs to draw, and nothing more. */
data class CaptureState(
    val recording: Boolean = false,
    val bufferedMs: Long = 0,
    val retentionMs: Long = 0,
    val peakLevel: Float = 0f,
    /** Increments once per emitted frame so the UI can advance a waveform in step
     *  with capture, rather than animating on a timer that knows nothing about it. */
    val sampleSeq: Long = 0,
)

/**
 * Continuous 16 kHz mono AAC capture into a rolling on-disk window.
 *
 * The whole pipeline lives on one thread driven by blocking `AudioRecord.read`,
 * which is what paces the loop — there is no timer, no polling and no second
 * thread to synchronise with. Commands from other threads are drained from a
 * lock-free queue between iterations (worst-case latency: one 64 ms frame).
 */
class RingRecorder(bufferDir: File) {

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

        /**
         * One emit per encoded frame (~15.6 Hz at 64 ms/frame). The waveform needs
         * this to move convincingly; when no UI is collecting it is just a field
         * write, and Compose stops collecting entirely once the screen is off.
         */
        const val STATE_EMIT_INTERVAL_FRAMES = 1
    }

    private val ring = SegmentRing(bufferDir)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

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
    private var emittedFrames = 0L

    fun start(retentionMinutes: Int) {
        if (running) {
            setRetention(retentionMinutes)
            return
        }
        retentionFrames = AudioSpec.framesForMinutes(retentionMinutes)
        running = true
        thread = Thread({ runLoop() }, "ring-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
        commands.clear()
        _state.value = CaptureState()
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
        post {
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

    fun release(window: PinnedWindow) = post { ring.unpin(window.segments) }

    private fun post(block: () -> Unit) {
        commands += block
    }

    // ------------------------------------------------------------------
    // Recorder thread
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller holds RECORD_AUDIO; service refuses to start otherwise
    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
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

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
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
            _state.value = _state.value.copy(recording = true)

            val info = MediaCodec.BufferInfo()
            val adtsHeader = ByteArray(Adts.headerSize())
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
                        peak = maxOf(peak, peakOf(input, read))
                        val ptsUs = samplesFed * 1_000_000L / AudioSpec.SAMPLE_RATE
                        codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                        samplesFed += read / 2
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, 0)
                        if (read < 0) throw IllegalStateException("AudioRecord.read failed: $read")
                    }
                }

                drainEncoder(codec, info, adtsHeader)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Capture loop stopped", t)
            _state.value = _state.value.copy(recording = false)
        } finally {
            running = false
            runCatching { audioRecord?.stop() }
            audioRecord?.release()
            runCatching { codec?.stop() }
            codec?.release()
            rollSegment()
            ring.clear()
            currentStream = null
            currentFile = null
        }
    }

    private fun drainCommands() {
        while (true) {
            (commands.poll() ?: return).invoke()
        }
    }

    private fun drainEncoder(codec: MediaCodec, info: MediaCodec.BufferInfo, adtsHeader: ByteArray) {
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) return

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val output = codec.getOutputBuffer(outIndex)!!
                Adts.writeHeader(adtsHeader, info.size)
                val stream = currentStream
                if (stream != null) {
                    stream.write(adtsHeader)
                    // Encoded frames are small (~200 B); a heap copy here is cheaper
                    // than keeping a channel open per segment.
                    val payload = ByteArray(info.size)
                    output.position(info.offset)
                    output.get(payload)
                    stream.write(payload)
                    onFrameWritten()
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun onFrameWritten() {
        currentFrames++
        if (currentFrames >= AudioSpec.FRAMES_PER_SEGMENT) rollSegment()

        if (++framesSinceEmit >= STATE_EMIT_INTERVAL_FRAMES) {
            framesSinceEmit = 0
            val buffered = (ring.bufferedFrames + currentFrames)
                .coerceAtMost(retentionFrames)
            _state.value = CaptureState(
                recording = true,
                bufferedMs = ClipBuilder.durationMs(buffered),
                retentionMs = ClipBuilder.durationMs(retentionFrames),
                peakLevel = peak,
                sampleSeq = ++emittedFrames,
            )
            peak = 0f
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
        runCatching {
            stream.flush()
            stream.close()
        }
        currentStream = null
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
