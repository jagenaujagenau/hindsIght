package com.warmly.watchrecorder.audio

import com.warmly.watchrecorder.shared.AudioSpec
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Minimal ADTS support.
 *
 * Segments are written as raw ADTS so each one is a standalone, playable `.aac`
 * file (invaluable when debugging on-device) and so a segment can be dropped
 * from the ring without touching any global container index. On save we strip
 * the headers again and hand the bare AAC frames to [android.media.MediaMuxer].
 */
object Adts {

    private const val HEADER_SIZE = 7
    private const val PROFILE_AAC_LC = 2 // MPEG-4 audio object type, minus one when written

    private val SAMPLE_RATE_INDEX = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350,
    )

    private val freqIndex: Int = SAMPLE_RATE_INDEX.indexOf(AudioSpec.SAMPLE_RATE).also {
        require(it >= 0) { "Unsupported sample rate ${AudioSpec.SAMPLE_RATE}" }
    }

    /**
     * AudioSpecificConfig (`csd-0`): 5 bits object type, 4 bits frequency index,
     * 4 bits channel config, zero-padded to 16 bits.
     */
    fun audioSpecificConfig(): ByteBuffer {
        val bits = (PROFILE_AAC_LC shl 11) or (freqIndex shl 7) or (AudioSpec.CHANNEL_COUNT shl 3)
        return ByteBuffer.wrap(byteArrayOf((bits shr 8).toByte(), bits.toByte()))
    }

    /** Fills [out] with the 7-byte header for a frame whose payload is [payloadSize] bytes. */
    fun writeHeader(out: ByteArray, payloadSize: Int) {
        val frameLength = payloadSize + HEADER_SIZE
        out[0] = 0xFF.toByte()
        // syncword low nibble | MPEG-4 | layer 00 | protection absent
        out[1] = 0xF1.toByte()
        out[2] = (((PROFILE_AAC_LC - 1) shl 6) or (freqIndex shl 2) or (AudioSpec.CHANNEL_COUNT shr 2)).toByte()
        out[3] = (((AudioSpec.CHANNEL_COUNT and 3) shl 6) or (frameLength shr 11)).toByte()
        out[4] = ((frameLength shr 3) and 0xFF).toByte()
        out[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
    }

    fun headerSize(): Int = HEADER_SIZE

    /**
     * Reads the next frame's payload from [input] into [dest], skipping the ADTS
     * header. Returns the payload size, or -1 at clean end of stream.
     *
     * @throws IOException if the stream is truncated mid-frame or loses sync.
     */
    fun readFrame(input: InputStream, header: ByteArray, dest: ByteArray): Int {
        val read = input.readNBytesCompat(header, HEADER_SIZE)
        if (read == 0) return -1
        if (read < HEADER_SIZE) throw IOException("Truncated ADTS header ($read bytes)")
        if (header[0] != 0xFF.toByte() || (header[1].toInt() and 0xF0) != 0xF0) {
            throw IOException("Lost ADTS sync")
        }

        val protectionAbsent = header[1].toInt() and 0x01 == 1
        val frameLength = ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xE0) shr 5)

        // CRC frames carry two extra bytes we never write, but tolerate on read.
        var payloadSize = frameLength - HEADER_SIZE
        if (!protectionAbsent) {
            input.skipExactly(2)
            payloadSize -= 2
        }
        if (payloadSize <= 0 || payloadSize > dest.size) {
            throw IOException("Implausible ADTS payload size $payloadSize")
        }

        if (input.readNBytesCompat(dest, payloadSize) != payloadSize) {
            throw IOException("Truncated ADTS payload")
        }
        return payloadSize
    }

    private fun InputStream.readNBytesCompat(dest: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = read(dest, total, count - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun InputStream.skipExactly(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) throw IOException("Truncated ADTS CRC")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun InputStream.skipExactly(count: Int) = skipExactly(count.toLong())
}
