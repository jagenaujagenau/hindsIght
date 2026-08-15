package earth.diego.hindsight.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

data class Appearance(
    val retention: Retention = Retention.DEFAULT,
    val waveStyle: WaveStyle = WaveStyle.DEFAULT,
    val accent: Accent = Accent.DEFAULT,
)

class RecorderSettings(private val context: Context) {

    private val retentionKey = intPreferencesKey("retention_minutes")
    private val waveKey = stringPreferencesKey("wave_style")
    private val accentKey = stringPreferencesKey("accent")

    val retention: Flow<Retention> = context.dataStore.data.map { prefs ->
        Retention.fromMinutes(prefs[retentionKey] ?: Retention.DEFAULT.minutes)
    }

    val waveStyle: Flow<WaveStyle> = context.dataStore.data.map { WaveStyle.from(it[waveKey]) }

    val accent: Flow<Accent> = context.dataStore.data.map { Accent.from(it[accentKey]) }

    val appearance: Flow<Appearance> =
        combine(retention, waveStyle, accent) { r, w, a -> Appearance(r, w, a) }

    suspend fun setRetention(retention: Retention) {
        context.dataStore.edit { it[retentionKey] = retention.minutes }
    }

    suspend fun setWaveStyle(style: WaveStyle) {
        context.dataStore.edit { it[waveKey] = style.name }
    }

    suspend fun setAccent(accent: Accent) {
        context.dataStore.edit { it[accentKey] = accent.name }
    }
}
