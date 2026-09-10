# Product source map

The native apps are Kotlin / Jetpack Compose, not React. No native components can be directly imported into the website. The website ports the drawing geometry, palette, strings and behavior into deterministic web presentation components. Screenshots were inspected as references; none ship in the page.

All paths below are relative to the repository root, two directories above this website.

| Website | Native source | What is derived |
| --- | --- | --- |
| `src/lib/product.ts` | `shared/src/main/java/earth/diego/hindsight/shared/WearProtocol.kt` | 16,000 Hz, mono, AAC-LC, 24,000 bps; 1,024 samples/frame; 469 frames/segment |
| Retention model | `wear/src/main/java/earth/diego/hindsight/data/RecorderSettings.kt` | 1/5/15/30/60 min; default 1 min; Bone accent #EDE7E1 |
| `WaveformMotion`, metering, watch SVG | `wear/src/main/java/earth/diego/hindsight/ui/{WaveformMotion,WaveScreen}.kt` | 31 samples; continuous baseline; quadratic edge taper; dB mapping, noise gate; 35ms attack / 120ms release |
| `WatchScreen`, `watchFeedback` | `wear/src/main/java/earth/diego/hindsight/ui/{WaveScreen,RecorderPresentation}.kt` | Listening, buffer fraction, tap hint, local-save vs phone confirmation; rounded duration |
| Buffer semantics | `wear/src/main/java/earth/diego/hindsight/audio/{SegmentRing,RingRecorder,ClipBuilder,AtomicClip}.kt` | duration-based retention, pinned saves, AAC remux into .m4a, final publication before transfer |
| Transfer narrative | `wear/src/main/java/earth/diego/hindsight/sync/{ClipUploadWorker,SyncListenerService}.kt`, `mobile/src/main/java/earth/diego/hindsight/mobile/sync/ClipReceiverService.kt` | .part → final file → ACK → watch deletes copy; keep/retry on failure |
| Phone colors/type | `mobile/src/main/java/earth/diego/hindsight/mobile/ui/theme/Theme.kt` | exact dark paper/rule/graphite/muted/trace/signal palette; sans UI, monospace metadata |
| `TimelineRows`, search | `mobile/src/main/java/earth/diego/hindsight/mobile/ui/{LibraryScreen,Timeline}.kt` | hour gutter, 42px waveforms, 56 bars, quiet stretches; case-insensitive title/transcript search |
| Phone waveforms | `mobile/src/main/java/earth/diego/hindsight/mobile/ui/components/WaveformView.kt` | mirrored rounded bars, peak-preserving downsampling, 1.7 width ratio, played signal color |
| Player island | `mobile/src/main/java/earth/diego/hindsight/mobile/ui/PlayerScreen.kt` | seek, ±10s, 0.75/1/1.5/2×, rename, confirmed delete and undo |
| Privacy qualifications | `mobile/src/main/java/earth/diego/hindsight/mobile/transcribe/Transcriber.kt`, root `README.md` | on-device recognizer, Android 13+, installed model, partial recall |

## Deliberate website adaptations

- Pixel Watch body and Pixel phone body are CSS presentation objects, not native UI. Device labels describe the repository’s tested hardware, not affiliation with Google.
- The source screenshots predate the current watch status/hint layout. The current `WaveScreen.kt` takes precedence. Bone is an actual selectable accent; the app defaults to the watch-face palette, not Bone.
- Manrope is the website’s display type. Device UI uses Roboto (Android sans equivalent), and metadata uses IBM Plex Mono (web equivalent of native monospace).
- All conversation fragments, timestamps, clips and audio levels are fictional demo fixtures. The website never requests microphone permission or stores recordings. The player is explicitly a **silent simulation**, not an audio transport. Sharing and transcription execution are not simulated.
- The buffer island advances 5 simulated seconds every 250ms (20×). It is a bounded sample model, not the native AAC/filesystem recorder. Old samples disappear at the selected window; native disk eviction is segment-granular with slack. Increasing retention cannot recover discarded samples.
- The demo shows the latest three saved snapshots in memory. This is a **website display limit only**; the native app never silently evicts saved clips. Reloading resets sample data.
- Storage is calculated in **MiB** from bitrate, duration and one 469-frame segment: `(minutes × 60 + 469 × 1024 / 16000) × 24000 / 8 / 1024²`. Container overhead and saved clips are excluded. Native settings use a rough KB/MB estimate; the website labels units explicitly rather than reproducing that approximation.
- The copy centers on staying present and deciding what matters afterward. “Let life move on” is supported by an explicit explanation that unsaved audio disappears. Listening starts only when the user enables it; the page does not imply the microphone is permanently active.
- The project is now MIT-licensed (root `LICENSE`), so the page says **open source**. There is no invented App Store / Play Store CTA.

## Keeping it current

Run `npm test` from this folder. The native-source test compares audio constants and retention choices when the full monorepo is available (skipped for standalone website copies). Review the mappings above after native UI, transfer, or transcription changes. Product UI state lives in `src/lib/product.ts` and React islands; scroll transforms live in `src/scripts/animation/story.ts`.
