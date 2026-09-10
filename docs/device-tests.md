# Native watch regression tests

## Status and scope

The instrumentation APK **builds**, but no device was connected during implementation.
These tests are **not claimed to pass on hardware**. JVM tests pass separately.

| Suite | Tests | What it exercises |
| --- | ---: | --- |
| `WatchUiTest` | 5 | Native Compose: permanent-denial settings action, save/discard separation, accessible saving without hints, transient confirmation with persistent queue state, 192 dp layout at 1.5× text scale. |
| `RecorderHardwareTest` | 5 | Real service/AudioRecord/AAC: screen-off capture, replacement capture threads, save-and-stop, system-silenced microphone cleanup, persisted timer expiry across activity recreation. |
| `RecorderSettingsDeviceTest` | 2 | Real DataStore: three-save hint threshold/reset and atomic clearing of deadline on Stop. |
| `PairedSyncHardwareTest` | 1 | Offline local save, unavailable-phone status, Bluetooth reconnect, and deletion only after the phone's durable ack. |

The hardware fixture checks that capture threads, the ongoing notification, and the
`hindsight:capture` partial wake lock are released. The interruption test changes the
microphone AppOp rather than revoking the runtime permission, which would kill the test
process. Production capture now treats a system-silenced recording client as an interruption,
not a healthy microphone recording a quiet room.

## Build

```sh
./gradlew :wear:assembleDebug :wear:assembleDebugAndroidTest :mobile:assembleDebug
```

Artifacts:

- `wear/build/outputs/apk/debug/wear-debug.apk`
- `wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk`
- `mobile/build/outputs/apk/debug/mobile-debug.apk`

All APKs used for Data Layer testing must share the application ID/signing key on the
watch and phone. Debug APKs built on one machine satisfy this.

## UI-only tests

These do not start the microphone or require a paired phone. An emulator can run them;
small/large physical round displays and TalkBack still need visual QA.

```sh
adb -s "$WATCH" install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s "$WATCH" install -r wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s "$WATCH" shell am instrument -w \
  -e class earth.diego.hindsight.ui.WatchUiTest \
  earth.diego.hindsight.test/androidx.test.runner.AndroidJUnitRunner
```

## Hardware tests — explicit opt-in

**Use a disposable debug install, an unlocked test watch and synthetic test speech.**
Do not run against your personal recording archive. The fixture requires an initially
empty outbox, grants microphone/notification permissions, changes recording/timer settings,
and removes the watch clips generated during the run. Any phone copies need manual cleanup.
It leaves recording stopped and the timer off. The AppOps test restores microphone access
to `allow` in `finally`; use a test device where that is the intended setting.

```sh
adb -s "$WATCH" shell am instrument -w \
  -e hardwareTests true \
  -e class earth.diego.hindsight.device.RecorderHardwareTest,earth.diego.hindsight.device.RecorderSettingsDeviceTest \
  earth.diego.hindsight.test/androidx.test.runner.AndroidJUnitRunner
```

Without `hardwareTests=true`, these tests are skipped **before** the fixture modifies
permissions or starts recording. A skip is not a pass.

## Paired-phone reconnect test — additional opt-in

Install/open the matching phone app first. Bluetooth must initially be enabled. The test
disables Bluetooth, saves while offline, then re-enables it and waits up to two minutes for
the matching phone acknowledgement. Bluetooth is restored in `finally`, even on assertion
failure or a skipped cloud-connected setup.

```sh
adb -s "$PHONE" install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s "$WATCH" shell am instrument -w \
  -e hardwareTests true -e pairedPhoneTests true \
  -e class earth.diego.hindsight.device.PairedSyncHardwareTest \
  earth.diego.hindsight.test/androidx.test.runner.AndroidJUnitRunner
```

If a cloud route remains reachable after Bluetooth is disabled, the test skips rather than
claiming to have exercised offline recovery. Use a Bluetooth-only Data Layer setup (for
example, an isolated LAN for wireless ADB), not ad hoc Wi-Fi changes that could sever ADB.
The test does not simulate a five-hour WorkManager backoff; keep that case in manual QA.

## Still requiring manual/physical validation

- Permission denial → app settings → permission grant → return, including process recreation.
  Runtime revocation can kill an in-process instrumentation runner, so the automated UI test
  covers the recovery action and the JVM test covers state selection, not this whole journey.
- TalkBack announcements, rotary input, round-screen clipping, colours and animation feel.
- OS process death/reboot mid-session, storage exhaustion and WorkManager quotas/backoff.
  Deadline arithmetic, storage policy and failed-save stop prevention have JVM tests, but
  those are not substitutes for these OS integrations.
- Battery/thermal cost and frame timing on optimized builds; see [validation](validation.md).

If an instrumentation run crashes before cleanup, restore your disposable test environment:
Bluetooth enabled, `appops set earth.diego.hindsight RECORD_AUDIO allow`, recording stopped.
Do not interpret successful APK assembly as successful device execution.
