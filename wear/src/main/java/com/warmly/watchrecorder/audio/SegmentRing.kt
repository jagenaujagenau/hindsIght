package com.warmly.watchrecorder.audio

import com.warmly.watchrecorder.shared.AudioSpec
import java.io.File

/** One closed ring-buffer segment on disk. */
data class Segment(
    val file: File,
    val frameCount: Int,
)

/**
 * Fixed-capacity circular buffer of encoded audio segments.
 *
 * Capacity is derived from the retention setting rather than fixed at the 60-minute
 * maximum, so a user who only wants the last 5 minutes pays 5 minutes of storage.
 *
 * Not thread-safe: every method is called from the recorder thread. The one
 * exception is deletion of *pinned* segments, which is deferred until [unpin] so
 * that a save in progress on an IO thread can never read a file out from under
 * itself.
 */
class SegmentRing(private val directory: File) {

    private val segments = ArrayDeque<Segment>()
    private val pinned = mutableSetOf<File>()
    private val pendingDelete = mutableListOf<File>()

    /** Number of segments kept, including one segment of slack for the trim margin. */
    private var capacity = 1

    val segmentCount: Int get() = segments.size

    /** Total frames currently retained, excluding the segment still being written. */
    var bufferedFrames: Int = 0
        private set

    init {
        directory.mkdirs()
        // A previous process may have died mid-recording; stale segments have no
        // in-memory frame counts, so they are unusable.
        directory.listFiles()?.forEach { it.delete() }
    }

    fun setRetentionFrames(retentionFrames: Int) {
        // +1 segment so a save can always reach back a *full* retention window even
        // when the oldest segment is only partially inside it.
        capacity = (retentionFrames + AudioSpec.FRAMES_PER_SEGMENT - 1) /
            AudioSpec.FRAMES_PER_SEGMENT + 1
        evict()
    }

    fun nextSegmentFile(index: Long): File = File(directory, "seg_%08d.aac".format(index))

    fun add(segment: Segment) {
        segments.addLast(segment)
        bufferedFrames += segment.frameCount
        evict()
    }

    private fun evict() {
        while (segments.size > capacity) {
            val oldest = segments.removeFirst()
            bufferedFrames -= oldest.frameCount
            delete(oldest.file)
        }
    }

    private fun delete(file: File) {
        if (file in pinned) pendingDelete += file else file.delete()
    }

    /**
     * Returns the newest segments covering at least [frames], oldest first, and
     * marks them undeletable until [unpin].
     */
    fun pinNewest(frames: Int): List<Segment> {
        val taken = ArrayDeque<Segment>()
        var covered = 0
        for (segment in segments.reversed()) {
            taken.addFirst(segment)
            covered += segment.frameCount
            if (covered >= frames) break
        }
        pinned += taken.map { it.file }
        return taken
    }

    fun unpin(pinnedSegments: List<Segment>) {
        pinned -= pinnedSegments.map { it.file }.toSet()
        val iterator = pendingDelete.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (file !in pinned) {
                file.delete()
                iterator.remove()
            }
        }
    }

    fun clear() {
        segments.forEach { delete(it.file) }
        segments.clear()
        bufferedFrames = 0
    }
}
