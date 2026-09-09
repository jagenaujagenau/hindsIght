package earth.diego.hindsight.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import earth.diego.hindsight.shared.AudioSpec
import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Assembles the newest [targetFrames] of buffered audio into a single `.m4a`.
 *
 * No re-encoding happens: the AAC frames written by the recorder are copied
 * straight into an MP4 container. Work scales with clip length but never incurs
 * the CPU cost of re-encoding. The outbox sees only a fully finalised file.
 */
object ClipBuilder {

    /** Comfortably above the largest frame AAC-LC will emit at this bitrate. */
    private const val MAX_FRAME_BYTES = 4096

    fun build(segments: List<Segment>, targetFrames: Int, output: File): File = AtomicClip.write(output) { partial ->
        require(segments.isNotEmpty() && targetFrames > 0) { "Nothing buffered" }

        val available = segments.sumOf { it.frameCount }
        check(available >= targetFrames) { "Incomplete recording window" }
        // Drop from the front so the clip ends at "now" — the user pressed save
        // because of what just happened, not what happened first.
        var framesToSkip = (available - targetFrames).coerceAtLeast(0)

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioSpec.SAMPLE_RATE,
            AudioSpec.CHANNEL_COUNT,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.BIT_RATE)
            setByteBuffer("csd-0", Adts.audioSpecificConfig())
        }

        val muxer = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val header = ByteArray(Adts.headerSize())
        val payload = ByteArray(MAX_FRAME_BYTES)
        val buffer = ByteBuffer.wrap(payload)
        val info = MediaCodec.BufferInfo()
        var written = 0

        try {
            val track = muxer.addTrack(format)
            muxer.start()
            for (segment in segments) {
                check(segment.file.exists()) { "A source segment is missing" }
                BufferedInputStream(segment.file.inputStream(), 32 * 1024).use { input ->
                    while (true) {
                        val size = Adts.readFrame(input, header, payload)
                        if (size < 0) break
                        if (framesToSkip > 0) {
                            framesToSkip--
                            continue
                        }
                        buffer.limit(size)
                        buffer.position(0)
                        info.set(0, size, written * AudioSpec.FRAME_DURATION_US, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(track, buffer, info)
                        written++
                    }
                }
            }
            check(written == targetFrames) { "Incomplete clip: $written of $targetFrames frames" }
            // A failed stop means an invalid MP4: never publish it as a saved clip.
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    fun durationMs(frames: Int): Long = frames * AudioSpec.FRAME_DURATION_US / 1000
}
