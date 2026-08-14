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
 * straight into an MP4 container, so a save costs a few hundred milliseconds of
 * pure IO regardless of clip length.
 */
object ClipBuilder {

    /** Comfortably above the largest frame AAC-LC will emit at this bitrate. */
    private const val MAX_FRAME_BYTES = 4096

    fun build(segments: List<Segment>, targetFrames: Int, output: File): File {
        require(segments.isNotEmpty()) { "Nothing buffered" }

        val available = segments.sumOf { it.frameCount }
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

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = muxer.addTrack(format)
        muxer.start()

        val header = ByteArray(Adts.headerSize())
        val payload = ByteArray(MAX_FRAME_BYTES)
        val buffer = ByteBuffer.wrap(payload)
        val info = MediaCodec.BufferInfo()
        var written = 0

        try {
            for (segment in segments) {
                if (!segment.file.exists()) continue
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
        } finally {
            // stop() throws if zero samples were written; release() must still run.
            runCatching { if (written > 0) muxer.stop() }
            muxer.release()
        }

        check(written > 0) { "No audio frames survived trimming" }
        return output
    }

    fun durationMs(frames: Int): Long = frames * AudioSpec.FRAME_DURATION_US / 1000
}
