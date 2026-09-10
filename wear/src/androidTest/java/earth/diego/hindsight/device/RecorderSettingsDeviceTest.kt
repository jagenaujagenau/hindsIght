package earth.diego.hindsight.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.data.SessionDeadline
import earth.diego.hindsight.data.SessionLimit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real DataStore; shares the disposable-install opt-in with hardware tests. */
@RunWith(AndroidJUnit4::class)
class RecorderSettingsDeviceTest : RecorderDeviceFixture() {
    @Test fun hintsBecomeQuietAfterThreeSuccessfulSavesAndCanBeRestored() = runBlocking {
        settings.resetHints()
        repeat(2) { settings.noteSuccessfulSave() }
        assertTrue(settings.showHints.first())
        settings.noteSuccessfulSave()
        assertFalse(RecorderSettings(context).showHints.first())
        settings.resetHints()
        assertTrue(RecorderSettings(context).showHints.first())
    }

    @Test fun stoppingPersistsIntentAndClearsTheOldSessionDeadline() = runBlocking {
        settings.setListening(true)
        settings.setSessionDeadline(SessionDeadline.start(SessionLimit.FIFTEEN, 1000, 2000, 1))
        assertNotNull(settings.sessionDeadline.first())
        settings.setListening(false)
        val reloaded = RecorderSettings(context)
        assertFalse(reloaded.listening.first())
        assertNull(reloaded.sessionDeadline.first())
    }
}
