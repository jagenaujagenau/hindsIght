# Hindsight website

A source-derived, cinematic Astro website for the Hindsight Wear OS / Android apps.

## Run

Requires Node **22.12+**.

```sh
npm ci
npm run dev -- --background
# http://localhost:4321
npx astro dev status
npx astro dev logs
npx astro dev stop
```

## Validate and build

```sh
npm test
npm run check
npm run test:browser # Requires Chrome; invokes agent-browser via npx
npm run build
npx astro preview --background --port 4322
npx astro preview stop
```

Deploy **`dist/`** to any static host. Build command: `npm ci && npm run build`. No adapter, server, database, runtime secrets or external APIs are required. Set `SITE_URL` to the actual public origin during the build to generate absolute social-image and canonical URLs. No deployment domain is assumed.

## Structure

```text
src/sections/                  Static Astro story beats
src/components/devices/         CSS Pixel Watch + Pixel phone; live HTML/SVG UI
src/components/hindsight/       Shared waveform + two client:visible React islands
src/lib/product.ts             Source-derived constants, metering, buffer state
src/scripts/animation/story.ts GSAP / ScrollTrigger presentation timelines
src/styles/global.css          Layered responsive styles and native palette
public/                        Favicon, social image
tests/product.test.ts         Model regression + native constant drift checks
```

The page shell is static Astro. Only the archive and retention demo hydrate. GSAP updates transforms/opacity directly, never React state on scroll. Live watch SVGs use the native attack/release interpolation and stop updating offscreen or in hidden tabs. The buffer demo pauses offscreen; sample playback stops when the archive leaves the viewport.

The system’s reduced-motion preference and the floating **Motion** control remove pinned choreography and reveal a normal readable document. Reduced-motion also starts the buffer demo paused. The visitor can step it manually or explicitly resume. Without JavaScript the full narrative and rendered device previews remain; sample controls require JavaScript.

## Product fidelity and limits

See **[docs/product-source.md](docs/product-source.md)** for native-file mappings, verified architecture, unit conventions, and deliberate adaptations.

- The actual source is Kotlin / Compose, so UI is ported, not imported as React.
- No screenshots, video, WebGL, analytics, remote fonts, or microphone access.
- Conversations and clips are clearly labeled fictional sample data. Playback is a **silent UI simulation**. Search, scrub, skip, speed, rename, delete/undo, retention and save-snapshot interactions work in memory.
- Native transcript limitations and ACK-before-delete behavior are disclosed accurately.
- Hindsight is open source under the root [MIT License](../../LICENSE). Bundled fonts retain their own licenses in `public/licenses/`.

## Verification

- Nine model/source-drift tests; Astro / TypeScript check; static production build.
- `npm run test:browser`: frame-by-frame first-load regression with a delayed animation bundle, desktop/mobile layouts, scroll reveal, motion toggle, no-JS markup, startup timeout, and reduced motion.
- Browser checks: desktop and 412px portrait; search filtering; rename; playback progress/speed; confirmed deletion + undo; retention changes, pause and save snapshots; scroll reversal; reduced-motion unpinning; no horizontal overflow at 412px.
- Axe: no violations in inspected archive/buffer and reduced-motion states. Automated checks do not replace a full screen-reader/device audit.
- Local production Lighthouse (mobile throttling): **98 Performance / 100 Accessibility / 100 Best Practices / 100 SEO**, **CLS 0**. Scores vary by machine and deployment; remeasure on the public host.

Re-run Lighthouse against the production preview, not the development server:

```sh
npx lighthouse http://localhost:4322 --chrome-flags='--headless' --view
```

`GOAL.md` is the original creative brief and remains intact.
