package earth.diego.hindsight.shared

/**
 * Contract shared by the watch and the phone. Both APKs must be built from these
 * constants — a mismatch here is silent (the Data Layer simply never delivers).
 */
object WearProtocol {

    /**
     * Capability advertised by the phone app (see mobile `res/values/wear.xml`).
     * The watch resolves this to find which connected node can accept clips.
     */
    const val CAPABILITY_CLIP_RECEIVER = "hindsight_clip_receiver"

    /** Channel path prefix. Full path is `/clip/<filename>.m4a`. */
    const val CHANNEL_CLIP_PREFIX = "/clip/"

    fun clipChannelPath(fileName: String): String = CHANNEL_CLIP_PREFIX + fileName

    fun fileNameFromChannelPath(path: String): String? =
        path.removePrefix(CHANNEL_CLIP_PREFIX)
            .takeIf { path.startsWith(CHANNEL_CLIP_PREFIX) && it.isNotBlank() && !it.contains('/') }
}

/**
 * Capture + encode parameters. Chosen for speech intelligibility at the lowest
 * practical cost: 16 kHz mono AAC-LC @ 24 kbps is ~180 KB per minute, so a full
 * 60-minute ring buffer costs ~10.5 MB of watch storage.
 */
object AudioSpec {
    const val SAMPLE_RATE = 16_000
    const val CHANNEL_COUNT = 1
    const val BIT_RATE = 24_000

    /** AAC-LC always emits 1024 PCM samples per frame. */
    const val SAMPLES_PER_FRAME = 1024

    /** 64 ms at 16 kHz — also the trim granularity when saving a clip. */
    const val FRAME_DURATION_US = SAMPLES_PER_FRAME * 1_000_000L / SAMPLE_RATE

    /** ~30 s per ring-buffer segment: coarse enough to keep file counts low. */
    const val FRAMES_PER_SEGMENT = 469

    fun framesForMinutes(minutes: Int): Int =
        ((minutes * 60L * 1_000_000L) / FRAME_DURATION_US).toInt()
}
