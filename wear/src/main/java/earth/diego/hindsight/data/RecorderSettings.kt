package earth.diego.hindsight.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "recorder_settings")

/** How far back a save reaches. Also sizes the ring buffer, hence the storage cost. */
enum class Retention(val minutes: Int, val label: String) {
    ONE(1, "1 min"),
    FIVE(5, "5 min"),
    FIFTEEN(15, "15 min"),
    THIRTY(30, "30 min"),
    SIXTY(60, "60 min");

    /** ~180 KB per minute at 24 kbps, plus one segment of slack. */
    val approxStorageMb: Float get() = (minutes + 0.5f) * 180f / 1024f

    companion object {
        val DEFAULT = ONE
        fun fromMinutes(minutes: Int): Retention =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

/**
 * How the listening state is drawn. All four are deliberately hard-edged and
 * machine-like rather than organic — this is an instrument that is listening,
 * not a music visualiser.
 */
enum class WaveStyle(val label: String, val description: String) {
    BARS("Bars", "Mirrored spectrum"),
    LINE("Line", "Oscilloscope trace"),
    PULSE("Pulse", "Concentric rings"),
    DOTS("Dots", "Minimal row");

    companion object {
        val DEFAULT = BARS
        fun from(name: String?): WaveStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * The wave is the entire interface, so its colour *is* the app's colour scheme.
 * [DYNAMIC] inherits the watch face palette; the rest are fixed instrument
 * colours that stay legible on black.
 */
enum class Accent(val label: String, val color: Color?) {
    DYNAMIC("Watch face", null),
    GREEN("Terminal", Color(0xFF3DDC84)),
    AMBER("Amber", Color(0xFFFFB300)),
    ICE("Ice", Color(0xFF6FD3FF)),
    MAGENTA("Magenta", Color(0xFFFF5FA2)),
    BONE("Bone", Color(0xFFEDE7E1));

    companion object {
        val DEFAULT = DYNAMIC
        fun from(name: String?): Accent = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How much of the bar height a given loudness earns.
 *
 * This is a taste setting, not a correctness one: rooms differ, and a scale that
 * feels alive in an office saturates in a cafe. The value is the dB window mapped
 * across the wave, so a wider window makes quiet sounds draw taller.
 */
enum class Sensitivity(val label: String, val rangeDb: Float, val description: String) {
    LOW("Low", 45f, "Smaller wave"),
    MEDIUM("Medium", 60f, "Balanced wave"),
    HIGH("High", 75f, "Larger wave for quiet sounds");

    companion object {
        val DEFAULT = MEDIUM
        fun from(name: String?): Sensitivity = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

data class Appearance(
    val retention: Retention = Retention.DEFAULT,
    val waveStyle: WaveStyle = WaveStyle.DEFAULT,
    val accent: Accent = Accent.DEFAULT,
    val sensitivity: Sensitivity = Sensitivity.DEFAULT,
)

class RecorderSettings(private val context: Context) {

    private val listeningKey = booleanPreferencesKey("listening")
    private val retentionKey = intPreferencesKey("retention_minutes")
    private val waveKey = stringPreferencesKey("wave_style")
    private val accentKey = stringPreferencesKey("accent")
    private val sensitivityKey = stringPreferencesKey("sensitivity")
    private val sessionLimitKey = intPreferencesKey("session_limit_minutes")
    private val endWallKey = longPreferencesKey("session_end_wall")
    private val endElapsedKey = longPreferencesKey("session_end_elapsed")
    private val bootCountKey = intPreferencesKey("session_boot")
    private val durationKey = longPreferencesKey("session_duration")
    private val successfulSavesKey = intPreferencesKey("successful_saves")

    /**
     * Whether the user wants to be listening — persisted, because it is intent,
     * not UI state.
     *
     * Holding this in the composition meant that if the service died (reinstall,
     * a system kill) while the activity was later restored from saved state, the
     * app decided it had "already auto-started" and never resumed. Defaults to
     * true so a first launch records immediately.
     */
    val listening: Flow<Boolean> = context.dataStore.data.map { it[listeningKey] ?: true }

    val retention: Flow<Retention> = context.dataStore.data.map { prefs ->
        Retention.fromMinutes(prefs[retentionKey] ?: Retention.DEFAULT.minutes)
    }

    val waveStyle: Flow<WaveStyle> = context.dataStore.data.map { WaveStyle.from(it[waveKey]) }

    val accent: Flow<Accent> = context.dataStore.data.map { Accent.from(it[accentKey]) }

    val sensitivity: Flow<Sensitivity> =
        context.dataStore.data.map { Sensitivity.from(it[sensitivityKey]) }

    val appearance: Flow<Appearance> =
        combine(retention, waveStyle, accent, sensitivity) { r, w, a, s -> Appearance(r, w, a, s) }

    val sessionLimit: Flow<SessionLimit> = context.dataStore.data.map {
        SessionLimit.fromMinutes(it[sessionLimitKey] ?: 0)
    }

    val sessionDeadline: Flow<SessionDeadline?> = context.dataStore.data.map { prefs ->
        val wall = prefs[endWallKey]
        val elapsed = prefs[endElapsedKey]
        val duration = prefs[durationKey]
        if (wall == null || elapsed == null || duration == null || duration <= 0) null
        else SessionDeadline(wall, elapsed, prefs[bootCountKey] ?: -1, duration)
    }

    val showHints: Flow<Boolean> = context.dataStore.data.map { (it[successfulSavesKey] ?: 0) < 3 }

    suspend fun noteSuccessfulSave() {
        context.dataStore.edit { it[successfulSavesKey] = ((it[successfulSavesKey] ?: 0) + 1).coerceAtMost(3) }
    }

    suspend fun resetHints() { context.dataStore.edit { it[successfulSavesKey] = 0 } }

    suspend fun setSessionLimit(limit: SessionLimit) {
        context.dataStore.edit { it[sessionLimitKey] = limit.minutes }
    }

    suspend fun setSessionDeadline(deadline: SessionDeadline?) {
        context.dataStore.edit {
            if (deadline == null) {
                it.remove(endWallKey); it.remove(endElapsedKey); it.remove(bootCountKey); it.remove(durationKey)
            } else {
                it[endWallKey] = deadline.wallTimeMs
                it[endElapsedKey] = deadline.elapsedTimeMs
                it[bootCountKey] = deadline.bootCount
                it[durationKey] = deadline.durationMs
            }
        }
    }

    suspend fun setListening(listening: Boolean) {
        context.dataStore.edit {
            it[listeningKey] = listening
            if (!listening) {
                it.remove(endWallKey); it.remove(endElapsedKey); it.remove(bootCountKey); it.remove(durationKey)
            }
        }
    }

    suspend fun setRetention(retention: Retention) {
        context.dataStore.edit { it[retentionKey] = retention.minutes }
    }

    suspend fun setWaveStyle(style: WaveStyle) {
        context.dataStore.edit { it[waveKey] = style.name }
    }

    suspend fun setSensitivity(sensitivity: Sensitivity) {
        context.dataStore.edit { it[sensitivityKey] = sensitivity.name }
    }

    suspend fun setAccent(accent: Accent) {
        context.dataStore.edit { it[accentKey] = accent.name }
    }
}
