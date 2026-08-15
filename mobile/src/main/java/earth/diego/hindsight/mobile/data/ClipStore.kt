package earth.diego.hindsight.mobile.data

import android.content.Context
import earth.diego.hindsight.mobile.audio.Waveform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class Clip(
    val file: File,
    val recordedAt: Long,
    val durationSeconds: Long,
    val sizeBytes: Long,
    /** User-given name, or null to fall back to the timestamp. */
    val title: String? = null,
    /**
     * Best-effort speech transcript, or null if none has been made. Partial by
     * nature — see [earth.diego.hindsight.mobile.transcribe.Transcriber].
     */
    val transcript: String? = null,
) {
    val id: String get() = file.name
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: ClipStore.timeOfDay(recordedAt)
    val hasWaveform: Boolean get() = Waveform.sidecarFor(file).exists()
}

/** A day's worth of clips, for a sectioned library. */
data class ClipDay(val label: String, val clips: List<Clip>)

/**
 * The phone is the archive. Clips are plain files; everything else about them —
 * a title, an amplitude envelope — lives in small sidecars next to the audio, so
 * there is no database to migrate and a clip is never separated from its metadata.
 */
object ClipStore {

    private val NAME_PATTERN = Regex("""clip_(\d{8}-\d{6})_(\d+)s\.m4a""")
    private val STAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    fun directory(context: Context): File =
        File(context.filesDir, "clips").apply { mkdirs() }

    /**
     * Deleted clips move here rather than being unlinked, so an accidental tap
     * costs nothing. These recordings cannot be recreated.
     */
    private fun trash(context: Context): File =
        File(context.filesDir, "trash").apply { mkdirs() }

    private fun titleFile(clip: File) = File(clip.parentFile, clip.name + ".title")

    fun transcriptFile(clip: File) = File(clip.parentFile, clip.name + ".txt")

    fun refresh(context: Context) {
        _clips.value = directory(context)
            .listFiles { f -> f.isFile && f.extension == "m4a" }
            .orEmpty()
            .map(::toClip)
            .sortedByDescending { it.recordedAt }
    }

    private fun toClip(file: File): Clip {
        val match = NAME_PATTERN.matchEntire(file.name)
        val recordedAt = match?.groupValues?.get(1)
            ?.let { runCatching { STAMP_FORMAT.parse(it)?.time }.getOrNull() }
        return Clip(
            file = file,
            recordedAt = recordedAt ?: file.lastModified(),
            durationSeconds = match?.groupValues?.get(2)?.toLongOrNull() ?: 0,
            sizeBytes = file.length(),
            title = runCatching { titleFile(file).takeIf { it.exists() }?.readText() }.getOrNull(),
            transcript = runCatching {
                transcriptFile(file).takeIf { it.exists() }?.readText()
            }.getOrNull(),
        )
    }

    fun rename(context: Context, clip: Clip, title: String) {
        val target = titleFile(clip.file)
        if (title.isBlank()) target.delete() else target.writeText(title.trim())
        refresh(context)
    }

    fun saveTranscript(context: Context, clip: File, text: String) {
        transcriptFile(clip).writeText(text)
        refresh(context)
    }

    /** Moves a clip and its sidecars to the trash. Reversible via [restore]. */
    fun moveToTrash(context: Context, clip: Clip): List<Pair<File, File>> {
        val moved = mutableListOf<Pair<File, File>>()
        val bin = trash(context)
        listOfNotNull(
            clip.file,
            Waveform.sidecarFor(clip.file).takeIf { it.exists() },
            titleFile(clip.file).takeIf { it.exists() },
            transcriptFile(clip.file).takeIf { it.exists() },
        ).forEach { source ->
            val destination = File(bin, source.name)
            if (source.renameTo(destination)) moved += source to destination
        }
        refresh(context)
        return moved
    }

    fun restore(context: Context, moved: List<Pair<File, File>>) {
        moved.forEach { (original, inTrash) -> inTrash.renameTo(original) }
        refresh(context)
    }

    fun purge(moved: List<Pair<File, File>>) {
        moved.forEach { (_, inTrash) -> inTrash.delete() }
    }

    /** Groups by calendar day so the library reads as a timeline, not a heap. */
    fun groupByDay(clips: List<Clip>): List<ClipDay> =
        clips.groupBy { startOfDay(it.recordedAt) }
            .toSortedMap(compareByDescending { it })
            .map { (day, items) -> ClipDay(dayLabel(day), items) }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayLabel(dayStart: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val oneDay = 24L * 60 * 60 * 1000
        return when (dayStart) {
            today -> "Today"
            today - oneDay -> "Yesterday"
            else -> SimpleDateFormat(
                if (isThisYear(dayStart)) "EEEE d MMMM" else "d MMMM yyyy",
                Locale.getDefault(),
            ).format(Date(dayStart))
        }
    }

    private fun isThisYear(millis: Long): Boolean {
        val year = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
        return year == Calendar.getInstance().get(Calendar.YEAR)
    }

    fun fullDate(millis: Long): String =
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(millis))

    fun timeOfDay(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    fun formatDuration(seconds: Long): String =
        "%d:%02d".format(seconds / 60, seconds % 60)

    fun formatPosition(millis: Int): String {
        val total = millis / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
