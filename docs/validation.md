# Watch performance and UX validation

## Changes

| Before | After |
| --- | --- |
| Tap immediately produced a success haptic; save events had no UI collector. | Replayable Saving/Saved/Failed/Empty state, a persistence-confirmation haptic, and separate phone-delivery acknowledgement. |
| Outbox could see a clip while the muxer was still writing it. | Build `.part`, validate the complete frame count and muxer finalization, fsync, then rename. Failed builds are removed; crash leftovers are pruned at the next serialized session initialization. |
| Two same-second saves could share a filename. | UUID suffix; the phone reads both legacy and new filenames. |
| Segment-count eviction assumed every segment lasted 30 seconds. | Duration-based eviction retains a full window even when saves repeatedly close short segments. |
| UI, notification, and tile owned different start/stop behavior; loading assumed listening. | Service-owned persisted intent. Restore waits for the real setting, and explicit starts do not repeat after composition recreation. |
| Capture failure could retain the partial wake lock and recording notification. | Terminal capture errors release both; the UI offers manual retry rather than an automatic restart loop. |
| Stop joined the capture thread on the service main thread for up to two seconds. | Serialized, suspending lifecycle; IO joins wait for actual termination before a replacement capture starts. |
| Reconnect work was appended behind a retry in exponential backoff. | Immediate wakeups never retry in-place; one independent delayed retry remains durable. A shared drain serializes transfers and includes newly saved clips. Short-lived queued wakeups cannot be blocked by retry backoff. |
| Cancellation could be swallowed by the uploader. | Cancellation propagates; failed/cancelled channels and callbacks are cleaned up with a bounded cleanup period. |
| The main face gave no buffer/action information or action semantics. | Listening/starting/error state, rounded buffer/target duration, action hints, accessible click/long-click labels, and polite save-result announcements. |
| Retention followed 13 cosmetic choices. | Recording controls and Save last first; cosmetic choices under Appearance, with fewer verbose rows. |
| Sensitivity copy suggested recording gain/filtering. | “Wave sensitivity — Display only; recording unchanged.” |
| Meter samples updated app-wide capture state at ~15.6 Hz. | Status at ~1 Hz; a visible, resumed waveform collector receives ephemeral levels. No peak calculation or meter emissions without that collector. |
| AAC payload, line paths and pulse drawing helpers allocated per frame. | Reused payload buffer, Path, and strokes; no per-frame pulse-index array. |
| Tile feedback stayed stale until its next scheduled refresh. | Refresh on recording transitions, saves and acknowledgements; multiline captions, round-screen padding and a 52 dp action target. Tile Start opens a visible permission-capable activity. |

## Waveform animation follow-up

| Before | After |
| --- | --- |
| Bars/dots jumped between ~64 ms audio samples. | One frame-clock loop interpolates cached targets: 35 ms attack, 120 ms release, no overshoot. Dot radii retain gaps and stay inside the canvas. |
| The line connected samples with sharp corners. | A reused cubic Bézier path rounds the crests; control points stay within adjacent sample heights and stroke bounds are inset. |
| Pulse rings jumped in radius with constant relative brightness. | Smoothed, time-offset energy changes radius and brightness, with extra room for the outer stroke. |
| Stop froze history and multiplied it by a fading amplitude. | History decays to zero with a 180 ms release while colour settles separately; rapid transitions retarget the current shape without snapping. |
| The visual meter drew directly on each sample. | Audio still arrives at ~15 Hz, but interpolation follows display frames only while needed. Silence settles exactly, background/page departure cancels the clock and collector, and return clears stale history. No background timer or artificial oscillation. |
| Only the stock stop animation handled system duration scale. | The custom meter interpolation also obeys it, including immediate target updates when animations are disabled. |
| No deterministic animation-model tests. | Ten tests cover interpolation, attack/release, retargeting, 30/60/120 Hz consistency, stop/silence settling, fresh return, duration scaling, and invalid input. |

This deliberately trades some visible-screen draw work for smoother motion; it does not
claim a battery improvement. Measure all four styles on an actual watch. In particular,
check quiet-to-speech response, rapid Stop/Start, leaving/re-entering the page, and Android's
animator duration scale at 0×, 1× and 2×. Drawing and lifecycle integration still need device
validation; the unit tests cover the pure motion model, not the Compose frame clock.

## Everyday reliability follow-up

The preceding reliability/waveform work was committed as `5281d9a`. These follow-ups add:

| Before | After |
| --- | --- |
| Long-press stopped immediately and discarded the buffer. | Long-press opens explicit save/discard/cancel choices. Settings and the notification offer Save & stop. Failed or cancelled saves cannot reach the destructive stop operation. |
| Undelivered audio had no storage visibility or reserve. | Sync & storage shows queued bytes/free space. Warn below 50 MiB free or at 100 MiB pending; preflight new recordings/saves against a 20 MiB reserve plus estimated additional bytes. Never evict saved clips to satisfy a quota. |
| Permission denial could lead to repeated requests with stale grant state. | An explicit permission screen offers Open settings; permanent denial avoids another request. ON_RESUME rechecks microphone permission after returning. |
| Pending count alone did not explain delivery delays. | Distinct queued/discovery/unavailable/sending/awaiting-ack/retry states and manual retry. Discovery and final status share the transfer lock; old acknowledgements cannot overwrite a newer transfer's state. |
| Recording could run indefinitely with no session control. | Optional 15/30/60-minute Save buffer & stop timer (default off), with persisted wall/monotonic deadlines and boot identity. Restores do not extend the session. The countdown remains live even after the audio ring fills. |
| No low-battery guidance. | A sticky battery broadcast drives an unplugged ≤15% warning in the UI/notification. It never silently stops capture. |
| Hints and saved messages stayed indefinitely. | Hints retire after three successful saves, with a Show gesture hints action. Five-second confirmations leave the pending indicator intact. A tile timeline expires its confirmation locally without a short wakeup timer. |
| Long safety text could crowd a small display at larger font scales. | The main face becomes scrollable above 1.1× text scale, with a smaller waveform; explicit safety messages remain reachable. |
| A policy-silenced microphone could resemble healthy recording of silence. | AudioRecord's recording-configuration callback detects system silencing and enters the existing terminal-error/resource-cleanup path. |
| No native regression suite. | Thirteen opt-in/native tests cover UI, larger text, actual capture, screen-off behavior, resource release, timer recreation, DataStore preferences, and paired-phone reconnect. See [device test commands and safety requirements](device-tests.md). |

Timer semantics are deliberate: expiry saves **only the current retention window**, not the
whole session. If a timer save fails, capture continues with a persistent warning; it does
not retry indefinitely or silently discard audio. The expired persisted deadline prevents a
later process restore from extending the failed session. There is no guarantee of executing
an expiry save if Android kills the process first; already-saved clips remain protected.

The new device tests have compiled but have **not run on hardware**. Native UI rendering,
permission-settings return, rotary/TalkBack behavior, OS kills/reboots, storage exhaustion,
long backoff and battery cost remain physical-validation items. A passing JVM policy test
or APK build does not establish those behaviors on a watch.

## Automated checks

Requires JDK 17+ and Android SDK 35. A Homebrew JDK may need an explicit `JAVA_HOME`:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :wear:testDebugUnitTest :mobile:testDebugUnitTest \
  :wear:assembleDebug :wear:assembleRelease :wear:assembleDebugAndroidTest :mobile:assembleDebug
```

The added lifecycle tests exercise the real start/stop/join code with controlled JVM
threads. They cannot validate AudioRecord, service destruction, or microphone foreground
restrictions. The queue tests exercise the real drain with temporary files, not Play
Services or the WorkManager scheduler. Do not interpret these tests as device profiling.

`:wear:lintDebug` currently crashes in the existing AGP/Compose lint combination with
`IncompatibleClassChangeError` (`KaSimpleVariableAccessCall` / `FrequentlyChangingValueDetector`).
No new suppressions or disabled checks were added to hide that failure.

## Physical-watch checks (not yet run)

Use a small round watch and a larger watch, with default and enlarged system text, TalkBack,
and rotary input. Check both supported older Wear OS versions and API 34+ microphone rules.
Use synthetic speech/test recordings, not private conversations.

| Scenario | Expected result |
| --- | --- |
| Clean install, permission grant; first start from tile | Permission-capable app opens; no foreground-service exception; recording begins after grant. |
| Choose Stop without saving from long-press/settings or the notification; reopen or recreate app | Remains stopped. Tile Start explicitly resumes. No duplicate capture threads. |
| Deny/revoke microphone permission or force an encoder/read failure | No false success; terminal error visible; no retained `hindsight:capture` wake lock or listening notification after cleanup. Manual retry starts a fresh engine. |
| Save immediately after starting | Saving then empty-buffer guidance, or a short valid clip; no success haptic for an empty/failed save. |
| Tap repeatedly during save; stop while saving | No duplicate in-flight save; requested local save finishes before its source is released. The UI remains responsive during shutdown. |
| One-minute retention, save every five seconds for two minutes | Later clips remain approximately one minute, not 15 seconds. |
| Save 1/15/60-minute windows; play on phone | Correct duration/content; newest audio retained; no truncated MP4s or exposed `.part` files. |
| Save twice in the same second | Distinct files and phone library entries with valid timestamps/durations. |
| Phone disconnected, save; reconnect while retry backoff is large | Immediate drain is not gated by delayed retry backoff. Only one transfer at a time; clips remain until acknowledged. Android may still defer WorkManager execution under OS quotas. |
| Save another clip while a transfer is running | Current drain picks it up; the late wakeup is not lost at worker completion. |
| Kill/disconnect during transfer; reconnect | File remains on watch; retry succeeds; no duplicate concurrent channels. |
| Successful save from tile, then open app | Latest local save result remains visible. After the matching ack, the result says “on phone.” |
| TalkBack and enlarged text | Save/Start/Retry and Stop are named; new results are announced without announcing the buffer counter every second. Safety text is reachable by scrolling at enlarged text sizes and remains within the circular safe area. |
| Appearance, rotary scroll, swipe back | Recording controls are easy to reach; sensitivity changes only the visual meter. |
| Leave waveform for settings; lower wrist; return | Meter work ceases without a visible resumed collector; capture continues. Returning shows fresh levels, not old history. |

## Measure performance on an optimized build

The repository's release APK is unsigned. For **local profiling only**, sign a copy with
the same development key as the phone debug APK (never distribute this key/build):

```sh
SDK="$ANDROID_HOME/build-tools/34.0.0"
"$SDK/apksigner" sign --ks "$HOME/.android/debug.keystore" \
  --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out /tmp/hindsight-wear-profile.apk \
  wear/build/outputs/apk/release/wear-release-unsigned.apk
adb -s "$WATCH" install -r /tmp/hindsight-wear-profile.apk
```

Compare the previous commit and this build under the same conditions. Do not compare a
debug build with a release build. Keep watch model, OS, battery range, screen brightness,
retention, radio connection, and synthetic audio source constant.

1. **Screen-off battery:** run 30–60 minutes of capture with no uploads, then with a fixed
   upload workload. Compare battery percentage/hour, CPU time and wake-lock duration. A
   partial wake lock while successfully recording is intentional; idle/error retention is not.
2. **Wave responsiveness:** measure all four styles, swipe to settings and back, and repeat
   Save/Stop/Start. Collect frame timing with Perfetto or `gfxinfo`; inspect allocations and
   main-thread stalls. Confirm there is no app-wide per-frame recomposition.
3. **Save latency:** measure tap-to-local-confirmation at 1, 15 and 60 minutes, at least ten
   runs each. Report median and p95; do not claim constant-time saves.
4. **Sync recovery:** record reconnect-to-worker-start and worker-start-to-phone-ack separately,
   including a long-backoff case. Measure retransmitted bytes and verify there are no
   overlapping transfers.

Useful captures (the reset commands affect diagnostic counters only):

```sh
adb -s "$WATCH" shell dumpsys gfxinfo earth.diego.hindsight reset
# Exercise the UI, then:
adb -s "$WATCH" shell dumpsys gfxinfo earth.diego.hindsight framestats > /tmp/hindsight-frames.txt
adb -s "$WATCH" shell dumpsys power > /tmp/hindsight-power.txt
adb -s "$WATCH" shell dumpsys activity services earth.diego.hindsight > /tmp/hindsight-services.txt
adb -s "$WATCH" shell dumpsys batterystats earth.diego.hindsight > /tmp/hindsight-battery.txt
```

Record model/OS/build, workload, number of runs, median/p95 latency, and battery results.
No measured performance gains or updated device screenshots are claimed until these checks run.
