package earth.diego.hindsight.mobile.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File

/** What a decode produced, so callers can describe the stream to consumers. */
data class PcmInfo(val file: File, val sampleRate: Int, val channels: Int, val bytes: Long)

/**
 * Decodes a clip to raw little-endian 16-bit PCM.
 *
 * The speech recogniser will not take an encoded file, only raw PCM, and our
 * clips happen to already be 16 kHz mono — the exact shape it wants — so nothing
 * is resampled or mixed down along the way.
 */
object PcmDecoder {

    fun decode(clip: File, destination: File): PcmInfo? {
        val extractor = MediaExtractor()
        extractor.setDataSource(clip.absolutePath)

        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null

        val format = extractor.getTrackFormat(track)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var written = 0L
        var inputDone = false

        try {
            BufferedOutputStream(destination.outputStream(), 64 * 1024).use { out ->
                while (true) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        val output = codec.getOutputBuffer(outIndex)
                        if (output != null && info.size > 0) {
                            val chunk = ByteArray(info.size)
                            output.position(info.offset)
                            output.get(chunk)
                            out.write(chunk)
                            written += info.size
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        return PcmInfo(destination, sampleRate, channels, written)
    }
}
