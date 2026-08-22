package earth.diego.hindsight.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import earth.diego.hindsight.shared.AudioSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
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
 * lock-free queue between frames (worst-case latency: one read chunk).
 *
 * ## Power
 *
 * This runs all day under a partial wake lock, so what it costs is not CPU work
 * per second but *how often it wakes the application processor*. Every wake ends
 * whatever idle state the SoC had reached. Three things follow from that, and
 * they are the reason the loop is shaped the way it is:
 *
 * - PCM is read a whole [chunkFrames] at a time rather than one AAC frame at a
 *   time. The audio HAL fills the AudioRecord buffer regardless; reading a second
 *   of it per wake instead of 64 ms cuts our wakes by 16x for identical output.
 * - The chunk shrinks to one frame only while a UI is actually collecting
 *   ([setLevelsEnabled]), because that is the only time the waveform's smoothness
 *   is worth paying for — and while the screen is on, the display dwarfs us anyway.
 * - Encoded audio accumulates in memory and reaches flash once per closed
 *   segment (~30 s), not once per full write buffer (~2.7 s).
 */
class RingRecorder(bufferDir: File) {

    private companion object {
        const val TAG = "RingRecorder"

        /** One AAC frame of PCM: 1024 samples, 16-bit mono. */
        const val FRAME_BYTES = AudioSpec.SAMPLES_PER_FRAME * 2

        /**
         * Frames read per wake while a UI is collecting.
         *
         * Deliberately 1, keeping the read 1:1 with encoded frames. Reading two
         * frames' worth while emitting state per encoded frame meant `peak` was
         * measured once but consumed twice, so every second frame reported a level
         * of zero and the waveform was half blank — which reads as "the mic is
         * barely working" even though the recording is fine.
         */
        const val CHUNK_FRAMES_ACTIVE = 1

        /** Frames read per wake with no UI attached: ~1.02 s of audio. */
        const val CHUNK_FRAMES_IDLE = 16

        /**
         * With no UI attached, state still has to move — the tile renders
         * `bufferedMs` on a 60 s freshness interval. ~5 s of audio per emit is
         * finer than anything that reads it, and 78x less traffic than per-frame.
         */
        const val IDLE_EMIT_INTERVAL_FRAMES = 78

        /**
         * One closed segment of ADTS AAC, generously: 469 frames of ~200 B payload
         * plus a 7 B header each is ~97 KB. Preallocated so a segment never grows.
         */
        const val SEGMENT_BUFFER_BYTES = 128 * 1024

        /** Long enough to be worth a wait, short enough to keep the loop honest. */
        const val DEQUEUE_TIMEOUT_US = 20_000L
    }

    private val ring = SegmentRing(bufferDir)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    @Volatile private var running = false
    private var thread: Thread? = null

    /**
     * The live capture, published so [stop] can break the loop out of a blocking
     * read. `AudioRecord.read` is not interruptible and now blocks for up to a
     * chunk; stopping the record underneath it returns immediately, which keeps
     * `stop()` — called on the main thread — from sitting there for a second.
     */
    @Volatile private var activeRecord: AudioRecord? = null

    // --- recorder-thread state, never touched from outside ---
    private var retentionFrames = AudioSpec.framesForMinutes(5)
    private var segmentIndex = 0L
    private val segmentBuffer = ByteArrayOutputStream(SEGMENT_BUFFER_BYTES)
    private var segmentOpen = false
    private var currentFrames = 0
    private var framesSinceEmit = 0
    private var peak = 0f
    private var emittedFrames = 0L
    private var levelsEnabled = false

    private val chunkFrames: Int
        get() = if (levelsEnabled) CHUNK_FRAMES_ACTIVE else CHUNK_FRAMES_IDLE

    private val emitIntervalFrames: Int
        get() = if (levelsEnabled) 1 else IDLE_EMIT_INTERVAL_FRAMES

    fun start(retentionMinutes: Int) {
        if (running) {
            setRetention(retentionMinutes)
            return
        }
        retentionFrames = AudioSpec.framesForMinutes(retentionMinutes)
        running = true
        // Audio priority, not *urgent* audio: a full chunk of slack per wake means
        // the loop no longer needs to pre-empt everything in sight, and staying out
        // of the urgent band lets the governor leave us on a little core.
        thread = Thread({ runLoop() }, "ring-recorder").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { activeRecord?.stop() }
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
     * Tells the recorder whether anything is drawing the waveform.
     *
     * Off — the common case, all day with the screen dark — the loop reads in
     * second-long chunks, skips level metering entirely and emits state rarely.
     * On, it reverts to per-frame capture so the wave moves at capture rate.
     */
    fun setLevelsEnabled(enabled: Boolean) = post {
        levelsEnabled = enabled
        // Whoever just attached should not wait up to 5 s for a first frame.
        if (enabled) framesSinceEmit = emitIntervalFrames
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "AudioRecord unavailable (min buffer $minBuffer)" }

            val chunkBytes = FRAME_BYTES * CHUNK_FRAMES_IDLE
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Room for two full idle chunks: the point of reading a second at a
                // time is that we may be asleep for that second, so the HAL needs
                // somewhere to put the audio without overrunning.
                maxOf(minBuffer, chunkBytes * 2),
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
            activeRecord = audioRecord

            codec = createEncoder()

            ring.setRetentionFrames(retentionFrames)
            openSegment()
            audioRecord.startRecording()
            _state.value = _state.value.copy(recording = true)

            val info = MediaCodec.BufferInfo()
            val adtsHeader = ByteArray(Adts.headerSize())
            val chunk = ByteArray(chunkBytes)
            var carry = 0
            var samplesFed = 0L

            while (running) {
                drainCommands()

                val want = chunkFrames * FRAME_BYTES - carry
                val read = audioRecord.read(chunk, carry, want, AudioRecord.READ_BLOCKING)
                if (read < 0) throw IllegalStateException("AudioRecord.read failed: $read")
                if (read == 0) {
                    // A stopped record returns 0 immediately and forever. Usually that
                    // is stop() deliberately breaking us out of the blocking read, and
                    // the loop condition is about to end us. Otherwise the framework
                    // took the mic, and spinning on it would burn a core flat — which
                    // is the exact opposite of the point.
                    check(!running || audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "AudioRecord stopped externally"
                    }
                    continue
                }

                val filled = carry + read
                // Whole frames only; a short read leaves its tail at the head of the
                // chunk for the next pass rather than feeding the encoder a runt.
                val consumed = encodeFrames(codec, chunk, filled, info, adtsHeader, samplesFed)
                samplesFed += consumed / 2
                carry = filled - consumed
                if (carry > 0 && consumed > 0) {
                    System.arraycopy(chunk, consumed, chunk, 0, carry)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Capture loop stopped", t)
            _state.value = _state.value.copy(recording = false)
        } finally {
            running = false
            activeRecord = null
            runCatching { audioRecord?.stop() }
            audioRecord?.release()
            runCatching { codec?.stop() }
            codec?.release()
            // No final roll: ring.clear() is about to delete everything anyway, and
            // the in-flight segment has never been recoverable across a restart.
            discardSegment()
            ring.clear()
        }
    }

    /**
     * Prefers a hardware AAC encoder when the device has one.
     *
     * `createEncoderByType` returns the first match, which on most watches is the
     * software encoder. At 24 kbps the CPU difference is small per frame, but this
     * runs every 64 ms for hours, and a DSP-backed codec keeps that off the AP.
     */
    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioSpec.SAMPLE_RATE,
            AudioSpec.CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
        }

        val codec = hardwareEncoderName(format)
            ?.let { runCatching { MediaCodec.createByCodecName(it) }.getOrNull() }
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

        return codec.apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun hardwareEncoderName(format: MediaFormat): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } }
            .firstOrNull { info ->
                // isHardwareAccelerated is API 29+; the name prefixes are the
                // long-standing convention for the two bundled software codecs.
                val software = info.name.startsWith("OMX.google.", true) ||
                    info.name.startsWith("c2.android.", true)
                !software && runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).isFormatSupported(format)
                }.getOrDefault(false)
            }
            ?.name

    /**
     * Feeds every whole frame in `chunk[0, byteCount)` to the encoder, returning
     * the number of bytes consumed. Commands are drained between frames so a save
     * still lands within a frame of the tap, not a chunk.
     */
    private fun encodeFrames(
        codec: MediaCodec,
        chunk: ByteArray,
        byteCount: Int,
        info: MediaCodec.BufferInfo,
        adtsHeader: ByteArray,
        samplesFedAtStart: Long,
    ): Int {
        var offset = 0
        var samplesFed = samplesFedAtStart
        while (byteCount - offset >= FRAME_BYTES) {
            drainCommands()

            val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inIndex < 0) {
                drainEncoder(codec, info, adtsHeader)
                continue
            }
            val input = codec.getInputBuffer(inIndex)!!
            input.clear()
            val size = minOf(input.capacity(), FRAME_BYTES)
            input.put(chunk, offset, size)
            // Metering is presentation only; with nothing drawing it, skip the scan.
            if (levelsEnabled) peak = maxOf(peak, peakOf(chunk, offset, size))

            codec.queueInputBuffer(inIndex, 0, size, samplesFed * 1_000_000L / AudioSpec.SAMPLE_RATE, 0)
            samplesFed += size / 2
            offset += size

            drainEncoder(codec, info, adtsHeader)
        }
        return offset
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
                if (segmentOpen) {
                    segmentBuffer.write(adtsHeader)
                    // Encoded frames are small (~200 B); a heap copy here is cheaper
                    // than keeping a channel open per segment.
                    val payload = ByteArray(info.size)
                    output.position(info.offset)
                    output.get(payload)
                    segmentBuffer.write(payload)
                    onFrameWritten()
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun onFrameWritten() {
        currentFrames++
        if (currentFrames >= AudioSpec.FRAMES_PER_SEGMENT) rollSegment()

        if (++framesSinceEmit >= emitIntervalFrames) {
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
        segmentBuffer.reset()
        segmentOpen = true
        currentFrames = 0
    }

    private fun discardSegment() {
        segmentBuffer.reset()
        segmentOpen = false
        currentFrames = 0
    }

    /**
     * Writes the in-flight segment to flash in one go and opens a fresh one.
     *
     * This is the only thing in the loop that touches storage, so it is also the
     * only thing that wakes the flash controller: once per full segment (~30 s),
     * or once per save.
     */
    private fun rollSegment() {
        if (!segmentOpen) return
        val frames = currentFrames
        segmentOpen = false
        if (frames > 0) {
            val file = ring.nextSegmentFile(segmentIndex++)
            val written = runCatching {
                file.writeBytes(segmentBuffer.toByteArray())
                true
            }.getOrElse {
                Log.e(TAG, "Segment write failed", it)
                file.delete()
                false
            }
            if (written) ring.add(Segment(file, frames))
        }
        currentFrames = 0
        if (running) openSegment() else segmentBuffer.reset()
    }

    private fun peakOf(buffer: ByteArray, offset: Int, byteCount: Int): Float {
        var max = 0
        // One sample in eight is plenty for a level meter and keeps this off the profiler.
        var i = 0
        while (i + 1 < byteCount) {
            val sample = ((buffer[offset + i + 1].toInt() shl 8) or (buffer[offset + i].toInt() and 0xFF)).toShort()
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
