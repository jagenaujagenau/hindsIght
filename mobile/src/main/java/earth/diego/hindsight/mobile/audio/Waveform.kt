package earth.diego.hindsight.mobile.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * A clip's amplitude envelope, reduced to a fixed number of buckets.
 *
 * Stored as one byte per bucket beside the audio, so a 60-minute recording costs
 * about 2 KB and opens instantly. Decoding on demand would put a multi-second
 * stall in front of the user at exactly the moment they want to listen.
 */
object Waveform {

    private const val TAG = "Waveform"
    private const val MAGIC = "HSPK1"

    /** Enough detail to scrub an hour accurately, small enough to load eagerly. */
    const val BUCKETS = 2000

    fun sidecarFor(clip: File): File = File(clip.parentFile, clip.name + ".peaks")

    fun read(clip: File): ByteArray? {
        val sidecar = sidecarFor(clip)
        if (!sidecar.exists()) return null
        return runCatching {
            sidecar.inputStream().use { input ->
                val magic = ByteArray(MAGIC.length)
                if (input.read(magic) != magic.size || String(magic) != MAGIC) return null
                val peaks = ByteArray(BUCKETS)
                var read = 0
                while (read < BUCKETS) {
                    val n = input.read(peaks, read, BUCKETS - read)
                    if (n < 0) break
                    read += n
                }
                if (read == BUCKETS) peaks else null
            }
        }.getOrNull()
    }

    /**
     * Decodes [clip] and writes its envelope sidecar. Runs faster than realtime —
     * this is 16 kHz mono AAC — but is still far too slow for the main thread.
     */
    fun generate(clip: File): Boolean {
        val peaks = runCatching { decodePeaks(clip) }.getOrElse {
            Log.w(TAG, "Could not decode ${clip.name}", it)
            null
        } ?: return false

        return runCatching {
            // Write to a temp file and rename, so a crash mid-write cannot leave a
            // half-formed sidecar that later reads as valid.
            val sidecar = sidecarFor(clip)
            val partial = File(sidecar.parentFile, sidecar.name + ".part")
            partial.outputStream().use { out ->
                out.write(MAGIC.toByteArray())
                out.write(peaks)
            }
            partial.renameTo(sidecar)
        }.getOrDefault(false)
    }

    private fun decodePeaks(clip: File): ByteArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(clip.absolutePath)

        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return null

        val format = extractor.getTrackFormat(track)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val totalFrames = (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1)
        val framesPerBucket = (totalFrames / BUCKETS).coerceAtLeast(1)

        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val peaks = ByteArray(BUCKETS)
        val info = MediaCodec.BufferInfo()
        var framesSeen = 0L
        var bucketPeak = 0
        var bucket = 0
        var inputDone = false

        try {
            while (bucket < BUCKETS) {
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
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val shorts = output.asShortBuffer()

                        var i = 0
                        while (i < shorts.limit()) {
                            val magnitude = abs(shorts.get(i).toInt())
                            if (magnitude > bucketPeak) bucketPeak = magnitude
                            i += channels // mono-ise by taking the first channel
                            framesSeen++
                            if (framesSeen >= framesPerBucket) {
                                peaks[bucket] = min(255, bucketPeak * 255 / 32768).toByte()
                                if (++bucket >= BUCKETS) break
                                framesSeen = 0
                                bucketPeak = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        // Trailing buckets stay at zero if the stream ended early, which is honest:
        // it means there genuinely was no audio there.
        return peaks
    }

    /**
     * Normalises for display against a high percentile rather than the absolute
     * maximum.
     *
     * A single handling knock or table bump is often 3-4x louder than any speech
     * in the clip. Scaling to the true maximum makes that one spike full height
     * and squashes the conversation — the part you actually want to see — into
     * the baseline. Clamping at the 95th percentile lets outliers clip instead.
     */
    fun normalised(peaks: ByteArray): FloatArray {
        val values = IntArray(peaks.size) { peaks[it].toInt() and 0xFF }
        val voiced = values.filter { it > 0 }.sorted()
        if (voiced.isEmpty()) return FloatArray(peaks.size)

        val reference = voiced[(voiced.size * 95 / 100).coerceIn(0, voiced.size - 1)]
            .coerceAtLeast(1)
        return FloatArray(peaks.size) { (values[it].toFloat() / reference).coerceIn(0f, 1f) }
    }
}
