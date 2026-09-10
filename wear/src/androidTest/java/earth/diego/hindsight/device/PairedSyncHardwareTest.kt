package earth.diego.hindsight.device

import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import earth.diego.hindsight.service.RecorderBus
import earth.diego.hindsight.service.RecorderService
import earth.diego.hindsight.service.SaveState
import earth.diego.hindsight.shared.WearProtocol
import earth.diego.hindsight.sync.ClipOutbox
import earth.diego.hindsight.sync.SyncState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Additionally opt in with pairedPhoneTests=true; restores Bluetooth in finally. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PairedSyncHardwareTest : RecorderDeviceFixture() {
    private fun phoneReachable(): Boolean = Tasks.await(
        Wearable.getCapabilityClient(context).getCapability(WearProtocol.CAPABILITY_CLIP_RECEIVER, CapabilityClient.FILTER_REACHABLE),
        5, TimeUnit.SECONDS,
    ).nodes.isNotEmpty()

    @Test fun reconnectDrainsOfflineSaveAndWaitsForDurablePhoneAck() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pairedPhoneTests") == "true")
        assumeTrue("Bluetooth must initially be enabled", Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) == 1)
        assumeTrue("Install the matching phone app first", phoneReachable())
        startRecorder()
        try {
            device.executeShellCommand("svc bluetooth disable")
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (phoneReachable() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
            // A cloud route is still a valid connection; do not pretend it tests offline recovery.
            assumeTrue("Cloud connection still reachable; use a Bluetooth-only test setup", !phoneReachable())
            onMain { RecorderService.save(context) }
            await("offline local save") { RecorderBus.save.value is SaveState.Saved }
            val saved = RecorderBus.save.value as SaveState.Saved
            val file = File(ClipOutbox.directory(context), saved.fileName)
            assertTrue(file.exists())
            assertFalse(saved.delivered)
            await("offline sync status", 30_000) { RecorderBus.sync.value == SyncState.PhoneUnavailable }
            device.executeShellCommand("svc bluetooth enable")
            await("reconnect and durable phone acknowledgement", 120_000) {
                val outcome = RecorderBus.save.value
                outcome is SaveState.Saved && outcome.fileName == saved.fileName && outcome.delivered && !file.exists()
            }
        } finally {
            device.executeShellCommand("svc bluetooth enable")
        }
    }
}
