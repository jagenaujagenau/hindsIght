package earth.diego.hindsight.device

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import earth.diego.hindsight.MainActivity
import earth.diego.hindsight.data.SessionDeadline
import earth.diego.hindsight.data.SessionLimit
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.RecorderService
import earth.diego.hindsight.service.SaveState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class RecorderHardwareTest : RecorderDeviceFixture() {
    @Test fun screenOffContinuesCapturing() {
        startRecorder()
        val buffered = RecorderBus.capture.value.bufferedMs
        try {
            device.sleep()
            await("buffer grows with the screen off", 10_000) { RecorderBus.capture.value.bufferedMs >= buffered + 2_000 }
            assertTrue(RecorderBus.capture.value.recording)
        } finally { device.wakeUp() }
    }

    @Test fun rapidStopStartNeverLeavesTwoMicrophonesOrAutoResumesAfterStop() {
        startRecorder()
        repeat(5) {
            val previousThread = Thread.getAllStackTraces().keys.single { it.name == "ring-recorder" && it.isAlive }
            onMain { RecorderService.stop(context); RecorderService.start(context) }
            await("a new capture thread replaces the old one") {
                !previousThread.isAlive && RecorderBus.capture.value.recording && captureThreads() == 1
            }
            assertTrue(runBlocking { settings.listening.first() })
        }
        onMain { RecorderService.stop(context) }
        await("explicit stop") { !RecorderBus.capture.value.recording && captureThreads() == 0 }
        scenario!!.recreate()
        instrumentation.waitForIdleSync()
        assertFalse(runBlocking { settings.listening.first() })
        assertFalse(RecorderBus.capture.value.recording)
        assertCaptureReleased()
    }

    @Test fun saveAndStopProducesARealEncodedClipBeforeReleasingCapture() {
        startRecorder()
        onMain { RecorderService.saveAndStop(context) }
        await("clip saved and recorder stopped") { RecorderBus.save.value is SaveState.Saved && !RecorderBus.capture.value.recording }
        val saved = RecorderBus.save.value as SaveState.Saved
        assertTrue(saved.durationMs >= 1_000)
        assertTrue(saved.sizeBytes > 0)
        assertFalse(runBlocking { settings.listening.first() })
        assertCaptureReleased()
    }

    @Test fun microphoneInterruptionReleasesResourcesInsteadOfRecordingSilentAudio() {
        startRecorder()
        try {
            // AppOps changes policy without revoking the runtime permission (which
            // would kill the instrumentation process). Restore it even if assertions fail.
            device.executeShellCommand("appops set ${context.packageName} RECORD_AUDIO ignore")
            await("microphone interruption reported") { RecorderBus.capture.value.error != null }
            assertFalse(RecorderBus.capture.value.recording)
            assertCaptureReleased()
        } finally {
            device.executeShellCommand("appops set ${context.packageName} RECORD_AUDIO allow")
        }
    }

    @Test fun restoredTimerSavesAndStopsWithoutExtendingOnActivityRecreation() {
        val durationMs = 8_000L
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        runBlocking {
            settings.setListening(true)
            settings.setSessionLimit(SessionLimit.FIFTEEN)
            // Seed a short persisted deadline; production choices remain 15/30/60 minutes.
            settings.setSessionDeadline(SessionDeadline(System.currentTimeMillis() + durationMs,
                SystemClock.elapsedRealtime() + durationMs, boot, durationMs))
        }
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        await("restored recording") { RecorderBus.capture.value.recording }
        scenario!!.recreate()
        await("timer saved buffer and stopped", 20_000) {
            RecorderBus.save.value is SaveState.Saved && !RecorderBus.capture.value.recording
        }
        assertFalse(runBlocking { settings.listening.first() })
        assertNull(runBlocking { settings.sessionDeadline.first() })
        assertCaptureReleased()
    }
}
