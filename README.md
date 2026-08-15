# Hindsight

*The last five minutes, always.*

A Wear OS app that continuously listens and keeps only the last *X* minutes. Press
**SAVE** and that window is written to an `.m4a` and pushed to the paired phone,
where a companion app lists and plays it.

The watch is a buffer, not an archive: a saved clip is deleted from the watch the
moment the phone confirms it landed.

## Modules

| Module    | What it is                                                          |
|-----------|---------------------------------------------------------------------|
| `:wear`   | Wear OS app — capture, ring buffer, clip assembly, transfer          |
| `:mobile` | Phone companion — receives, stores and plays clips                   |
| `:shared` | The Data Layer contract and audio parameters both sides must agree on |

Both apps use the applicationId `earth.diego.hindsight`. That is deliberate and
required: the Data Layer only bridges apps sharing an applicationId **and** a
signing key. Debug builds from the same machine share `~/.android/debug.keystore`,
so pairing works out of the box.

## How the buffer works

```
AudioRecord (16 kHz mono PCM, blocking read — this paces the whole loop)
    ↓
MediaCodec AAC-LC @ 24 kbps  (hardware encoder, synchronous mode)
    ↓  ADTS frames, 1024 samples = 64 ms each
Ring buffer of ~30 s segment files    buffer/seg_00000000.aac …
    oldest segment deleted once the window is full
    ↓  on SAVE
Pin the newest N segments → strip ADTS headers → MediaMuxer → clip_*.m4a
```

**Nothing is re-encoded on save.** The AAC frames the encoder already produced are
copied straight into an MP4 container, so saving 60 minutes costs the same CPU as
saving 1 — a few hundred milliseconds of pure IO.

**Trimming is frame-exact.** Because the recorder tracks frame counts per segment,
a save drops leading frames until exactly *X* minutes remain, to a 64 ms
granularity. It never returns a clip rounded up to a segment boundary.

**A save never stalls capture.** `pinNewest` marks the chosen segments undeletable
and the muxing runs off-thread; eviction that would have deleted a pinned file
defers until the save releases it.

### Storage and power

| Retention | Ring buffer on disk |
|-----------|---------------------|
| 1 min     | ~0.3 MB             |
| 5 min     | ~1.0 MB             |
| 15 min    | ~2.7 MB             |
| 30 min    | ~5.4 MB             |
| 60 min    | ~10.6 MB            |

The ring is sized from the *current* retention setting, not the 60-minute maximum,
so a 5-minute user pays for 5 minutes. Lowering the setting reclaims the space
immediately.

Other choices made for battery and CPU:

- One thread runs capture, encode and disk writes; there is no timer and no
  polling. `AudioRecord.read` blocking is the clock.
- Cross-thread commands arrive on a lock-free queue drained between iterations
  (worst case one 64 ms frame of latency).
- The encoder writes through an 8 KB buffer — roughly one `write` syscall every
  2.7 s, which also caps loss to ~3 s if the process is killed.
- UI state is published at ~4 Hz, not per frame.
- Compose icon libraries are deliberately not used on the watch; the controls are
  text. Release APK: **2.5 MB** (watch), **1.1 MB** (phone).

## Transfer

The phone advertises the capability `hindsight_clip_receiver`
(`mobile/src/main/res/values/wear.xml`). The watch resolves it, opens a
`ChannelClient` channel at `/clip/<filename>.m4a` and streams the file.

Delivery is driven by a WorkManager job with exponential backoff, so clips saved
while the phone is out of range queue in `outbox/` and go out when it returns. The
phone writes to a `.part` file and renames on success, so an interrupted transfer
never surfaces a truncated clip.

**Transport success is not delivery.** `ChannelClient.sendFile` resolves once the
bytes reach the local Bluetooth buffer — the peer may never have seen them. So the
watch does not delete anything on send. After writing a clip to disk the phone
sends `/clip-ack/<filename>`, and only that message authorises the watch to
reclaim its copy. A transfer that dies in flight therefore costs a retry, not a
recording. Re-sending a clip the phone already holds is harmless: it re-acks.

Two Wear-specific traps are worth calling out, because both fail *silently*:

- **Never put `android:permission` on a `WearableListenerService`.** That attribute
  constrains the caller, and Google Play services does not hold
  `BIND_LISTENER_SERVICE`. Declaring it makes GMS permanently unable to bind, so
  channel events queue up and are dropped with a `SecurityException` in GMS's own
  log — the app sees nothing at all.
- **Never close a channel straight after `sendFile`.** It aborts the in-flight
  transfer. `sendFile` closes the output stream itself; wait for `onOutputClosed`.

## Build and run

Requires JDK 17 and the Android SDK (compileSdk 35).

```bash
./gradlew :wear:assembleDebug :mobile:assembleDebug
./gradlew :wear:testDebugUnitTest
```

Install to a paired pair of devices:

```bash
adb -s <phone-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Install the phone app **first** — its capability has to be registered before the
watch will find a node to send to. If you install in the other order, the first
upload attempt returns `retry` and WorkManager delivers it a few seconds later
anyway.

On the watch: grant the microphone permission, tap **REC**, tap **···** to change
how far back **SAVE** reaches.

## Tests

`./gradlew :wear:testDebugUnitTest` covers the two places a bug would silently
corrupt audio rather than crash:

- ADTS header write/read round-trip, and that the header encodes 16 kHz mono
  AAC-LC with a correct frame length.
- Ring buffer eviction, retention shrink reclaiming storage, and that pinned
  segments survive eviction during a save then are actually deleted on release.

Capture, encoding and the Data Layer transfer need real hardware; they are not
covered by unit tests.
