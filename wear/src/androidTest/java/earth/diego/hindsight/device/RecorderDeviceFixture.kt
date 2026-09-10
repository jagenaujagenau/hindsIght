package earth.diego.hindsight.device

import android.Manifest
import android.app.NotificationManager
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import earth.diego.hindsight.MainActivity
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.data.RecorderSettings
import earth.diego.hindsight.data.SessionLimit
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.RecorderService
import earth.diego.hindsight.service.SaveState
import earth.diego.hindsight.sync.ClipOutbox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before

/** Opt-in only: use a disposable debug install, an unlocked watch, and synthetic audio. */
abstract class RecorderDeviceFixture {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val device = UiDevice.getInstance(instrumentation)
    protected val settings = RecorderSettings(context)
    protected var scenario: ActivityScenario<MainActivity>? = null
    private var enabled = false

    @Before fun prepareRecorderDevice() {
        assumeTrue("Pass -e hardwareTests true on a disposable watch install",
            InstrumentationRegistry.getArguments().getString("hardwareTests") == "true")
        assumeTrue("Outbox must be empty; tests never delete existing saved clips", ClipOutbox.pending(context).isEmpty())
        enabled = true
        device.wakeUp()
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        onMain { RecorderService.stop(context) }
        await("previous recorder service stopped") { captureThreads() == 0 && !RecorderBus.capture.value.recording && !recorderServiceRunning() }
        runBlocking {
            settings.setListening(false)
            settings.setSessionLimit(SessionLimit.OFF)
            settings.setSessionDeadline(null)
        }
        RecorderBus.publish(CaptureState())
        RecorderBus.publish(SaveState.Idle)
    }

    protected fun startRecorder() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START_LISTENING, true))
        await("microphone recording") { RecorderBus.capture.value.recording }
        await("audio buffered") { RecorderBus.capture.value.bufferedMs >= 1_000 }
    }

    protected fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    protected fun captureThreads() = Thread.getAllStackTraces().keys.count { it.name == "ring-recorder" && it.isAlive }

    @Suppress("DEPRECATION") // API 26+ still exposes the calling app's own services.
    private fun recorderServiceRunning() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == RecorderService::class.java.name }

    protected fun await(description: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Timed out: $description; capture=${RecorderBus.capture.value}; save=${RecorderBus.save.value}", predicate())
    }

    protected fun assertCaptureReleased() {
        await("capture thread released") { captureThreads() == 0 }
        await("ongoing notification removed") {
            context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 1 }
        }
        val activeWakeLock = device.executeShellCommand("dumpsys power").lineSequence()
            .any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("hindsight:capture") }
        assertTrue("Capture wake lock must be released", !activeWakeLock)
    }

    @After fun cleanRecorderDevice() {
        if (!enabled) return
        try {
            onMain { RecorderService.stop(context) }
            await("test recording stopped") { captureThreads() == 0 && !RecorderBus.capture.value.recording && !recorderServiceRunning() }
        } finally {
            device.wakeUp()
            scenario?.close()
            runBlocking { settings.setListening(false); settings.setSessionLimit(SessionLimit.OFF) }
            // The outbox was empty before this test; only recordings created by
            // this disposable test run are removed. Phone copies need manual cleanup.
            ClipOutbox.pending(context).forEach { it.delete() }
            ClipOutbox.refreshStatus(context)
        }
    }
}
