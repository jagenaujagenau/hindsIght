package earth.diego.hindsight.mobile.transcribe

import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min

/** A byte range of a PCM file, chosen to start and end in relative quiet. */
data class Chunk(val startByte: Long, val endByte: Long) {
    val bytes: Long get() = endByte - startByte
}

/**
 * Splits raw 16-bit PCM into recogniser-sized pieces.
 *
 * Needed because the on-device recogniser stops at the first substantial silence
 * regardless of `EXTRA_SEGMENTED_SESSION` — measured on a Pixel 10, a 59-second
 * clip containing three spoken passages returned only the first. Feeding it one
 * bounded chunk at a time is what makes a long recording transcribe in full.
 *
 * Boundaries are nudged to the quietest point nearby so cuts land between words
 * rather than through them.
 */
object PcmChunker {

    /** Long enough to give the recogniser context, short enough that it will not bail. */
    private const val CHUNK_SECONDS = 20

    /** How far a boundary may move to find silence. */
    private const val SNAP_SECONDS = 3

    /** Granularity of the search for a quiet moment. */
    private const val PROBE_MS = 100

    /**
     * Each chunk reaches back into its predecessor, so speech sitting on a
     * boundary is heard whole by at least one of them. Costs a little duplicated
     * audio; missing a sentence entirely costs far more.
     */
    private const val OVERLAP_SECONDS = 3

    fun chunk(pcm: java.io.File, sampleRate: Int, channels: Int): List<Chunk> {
        val bytesPerSample = 2 * channels
        val bytesPerSecond = sampleRate.toLong() * bytesPerSample
        val total = pcm.length()
        if (total <= 0) return emptyList()

        val target = CHUNK_SECONDS * bytesPerSecond
        if (total <= target) return listOf(Chunk(0, total))

        val chunks = mutableListOf<Chunk>()
        RandomAccessFile(pcm, "r").use { file ->
            var start = 0L
            while (start < total) {
                val ideal = start + target
                if (ideal >= total) {
                    chunks += Chunk(start, total)
                    break
                }
                val end = quietestNear(file, ideal, SNAP_SECONDS * bytesPerSecond, bytesPerSecond, total)
                    .coerceAtLeast(start + bytesPerSecond) // never emit a degenerate chunk
                chunks += Chunk(start, end)
                start = (end - OVERLAP_SECONDS * bytesPerSecond).coerceAtLeast(start + bytesPerSecond)
                start -= start % 2
            }
        }
        return chunks
    }

    /**
     * Finds the quietest [PROBE_MS] window within +/- [radius] bytes of [ideal],
     * and returns its centre, aligned to a sample boundary.
     */
    private fun quietestNear(
        file: RandomAccessFile,
        ideal: Long,
        radius: Long,
        bytesPerSecond: Long,
        total: Long,
    ): Long {
        val probe = (bytesPerSecond * PROBE_MS / 1000).toInt().coerceAtLeast(2)
        val from = (ideal - radius).coerceAtLeast(0)
        val to = (ideal + radius).coerceAtMost(total)
        if (to - from < probe) return ideal

        val buffer = ByteArray(probe)
        var bestOffset = ideal
        var bestEnergy = Long.MAX_VALUE

        var offset = from
        while (offset + probe <= to) {
            file.seek(offset)
            file.readFully(buffer)

            var energy = 0L
            var i = 0
            // Every eighth sample is plenty to rank windows against each other.
            while (i + 1 < buffer.size) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                energy += abs(sample.toInt())
                i += 16
            }

            if (energy < bestEnergy) {
                bestEnergy = energy
                bestOffset = offset + probe / 2
            }
            offset += probe
        }

        // Align down to a whole sample so the slice never starts mid-sample.
        return min(bestOffset - (bestOffset % 2), total)
    }
}
