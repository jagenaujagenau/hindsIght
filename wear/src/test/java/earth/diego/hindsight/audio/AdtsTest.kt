package earth.diego.hindsight.audio

import earth.diego.hindsight.shared.AudioSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class AdtsTest {

    @Test
    fun `header round-trips through the reader`() {
        val payloads = List(50) { size -> Random(size).nextBytes(80 + size * 3) }

        val encoded = ByteArrayOutputStream().apply {
            val header = ByteArray(Adts.headerSize())
            payloads.forEach { payload ->
                Adts.writeHeader(header, payload.size)
                write(header)
                write(payload)
            }
        }.toByteArray()

        val input = ByteArrayInputStream(encoded)
        val header = ByteArray(Adts.headerSize())
        val scratch = ByteArray(4096)

        payloads.forEachIndexed { index, expected ->
            val size = Adts.readFrame(input, header, scratch)
            assertEquals("frame $index size", expected.size, size)
            assertArrayEquals("frame $index payload", expected, scratch.copyOf(size))
        }
        assertEquals("stream should be exhausted", -1, Adts.readFrame(input, header, scratch))
    }

    @Test
    fun `header encodes the configured sample rate and channel count`() {
        val header = ByteArray(Adts.headerSize())
        Adts.writeHeader(header, 200)

        assertEquals(0xFF.toByte(), header[0])
        assertEquals(0xF1.toByte(), header[1])

        val frequencyIndex = (header[2].toInt() shr 2) and 0x0F
        assertEquals("16 kHz is index 8", 8, frequencyIndex)

        val channelConfig = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() shr 6) and 0x03)
        assertEquals(AudioSpec.CHANNEL_COUNT, channelConfig)

        val frameLength = ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xE0) shr 5)
        assertEquals(200 + Adts.headerSize(), frameLength)
    }

    @Test
    fun `audio specific config matches AAC-LC 16 kHz mono`() {
        val csd = Adts.audioSpecificConfig()
        // 00010 1000 0001 000 -> object type 2, freq index 8, channel config 1
        assertEquals(0x14.toByte(), csd.get(0))
        assertEquals(0x08.toByte(), csd.get(1))
    }

    @Test
    fun `retention converts to a whole number of frames`() {
        // 5 minutes at 64 ms per frame
        assertEquals(4687, AudioSpec.framesForMinutes(5))
        assertEquals(56250, AudioSpec.framesForMinutes(60))
    }
}
