package earth.diego.hindsight.mobile.data

import android.content.Context
import earth.diego.hindsight.mobile.audio.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /**
     * Resolved once when the library is read, never on demand. As a computed
     * property this stat ran per row on every recomposition — on the main thread,
     * during scrolling — because it was used as a LaunchedEffect key.
     */
    val hasWaveform: Boolean = false,
) {
    val id: String get() = file.name
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: ClipStore.timeOfDay(recordedAt)
}

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

    /**
     * Re-reads the library. Suspends because it is several file operations per
     * clip — including reading each transcript — which is far too much for the
     * main thread once there are more than a handful of recordings.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) { refreshNow(context) }

    /** For callers already on a background thread, such as services and workers. */
    fun refreshNow(context: Context) {
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
            hasWaveform = Waveform.sidecarFor(file).exists(),
        )
    }

    suspend fun rename(context: Context, clip: Clip, title: String) = withContext(Dispatchers.IO) {
        val target = titleFile(clip.file)
        if (title.isBlank()) target.delete() else target.writeText(title.trim())
        refreshNow(context)
    }

    fun saveTranscript(context: Context, clip: File, text: String) {
        transcriptFile(clip).writeText(text)
        refreshNow(context)
    }

    /** Moves a clip and its sidecars to the trash. Reversible via [restore]. */
    suspend fun moveToTrash(context: Context, clip: Clip): List<Pair<File, File>> = withContext(Dispatchers.IO) {
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
        refreshNow(context)
        moved
    }

    suspend fun restore(context: Context, moved: List<Pair<File, File>>) = withContext(Dispatchers.IO) {
        moved.forEach { (original, inTrash) -> inTrash.renameTo(original) }
        refreshNow(context)
    }

    suspend fun purge(moved: List<Pair<File, File>>) = withContext(Dispatchers.IO) {
        moved.forEach { (_, inTrash) -> inTrash.delete() }
    }

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dayLabel(dayStart: Long): String {
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
