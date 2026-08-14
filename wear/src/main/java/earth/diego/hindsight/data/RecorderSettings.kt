package earth.diego.hindsight.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        val DEFAULT = FIVE
        fun fromMinutes(minutes: Int): Retention =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

class RecorderSettings(private val context: Context) {

    private val retentionKey = intPreferencesKey("retention_minutes")

    val retention: Flow<Retention> = context.dataStore.data.map { prefs ->
        Retention.fromMinutes(prefs[retentionKey] ?: Retention.DEFAULT.minutes)
    }

    suspend fun setRetention(retention: Retention) {
        context.dataStore.edit { it[retentionKey] = retention.minutes }
    }
}
