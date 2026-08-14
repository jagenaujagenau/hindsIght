package earth.diego.hindsight.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(
    val file: File,
    val receivedAt: Long,
    val durationSeconds: Long,
    val sizeBytes: Long,
) {
    val id: String get() = file.name
}

/**
 * The phone is the archive. Clips are plain files in app storage and their metadata
 * comes from the watch's filename (`clip_<stamp>_<seconds>s.m4a`), so nothing has to
 * be decoded or indexed just to draw the list.
 */
object ClipStore {

    private val NAME_PATTERN = Regex("""clip_(\d{8}-\d{6})_(\d+)s\.m4a""")
    private val STAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    fun directory(context: Context): File =
        File(context.filesDir, "clips").apply { mkdirs() }

    fun refresh(context: Context) {
        _clips.value = directory(context)
            .listFiles { f -> f.isFile && f.extension == "m4a" }
            .orEmpty()
            .map(::toClip)
            .sortedByDescending { it.receivedAt }
    }

    private fun toClip(file: File): Clip {
        val match = NAME_PATTERN.matchEntire(file.name)
        val recordedAt = match?.groupValues?.get(1)
            ?.let { runCatching { STAMP_FORMAT.parse(it)?.time }.getOrNull() }
        return Clip(
            file = file,
            receivedAt = recordedAt ?: file.lastModified(),
            durationSeconds = match?.groupValues?.get(2)?.toLongOrNull() ?: 0,
            sizeBytes = file.length(),
        )
    }

    fun delete(context: Context, clip: Clip) {
        clip.file.delete()
        refresh(context)
    }

    fun formatTimestamp(millis: Long): String =
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(millis))

    fun formatDuration(seconds: Long): String =
        "%d:%02d".format(seconds / 60, seconds % 60)
}
