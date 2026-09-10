# Hindsight — Cinematic Website Build Prompt

Build an award-winning, cinematic product website for **Hindsight**.

You have full access to the Hindsight source code. Treat the repository as the source of truth for the product, UI, behavior, terminology, colors, typography, interaction model, and technical architecture.

Do not invent a fake product presentation.

Before writing the website, inspect the codebase carefully:

- understand how Hindsight actually works
- inspect the Wear OS UI
- inspect the phone UI
- identify reusable React/UI components
- identify design tokens, icons, typography, colors, spacing, waveform logic, and animations
- inspect any screenshots or design assets already present
- understand the ring-buffer and transfer behavior
- understand which parts of the UI can be rendered directly from code

Whenever possible, **recreate the real app UI using web components derived from the source code instead of embedding screenshots**.

Screenshots should be a fallback, not the default.

The website should feel like the product itself has escaped from the watch and become an interactive cinematic story.

## Stack

Use:

- Astro
- React islands where interaction is required
- TypeScript
- GSAP
- GSAP ScrollTrigger
- CSS / SVG / Canvas for graphics
- Three.js only if absolutely necessary

Prefer Astro for the overall page shell and static content.

Use React only for interactive/product-demo sections.

Use GSAP for cinematic scroll choreography.

Avoid unnecessary framework complexity.

The final output must be production-ready, performant, responsive, accessible, and deployable as a normal Astro site.

## Core Product Idea

Hindsight exists because:

**You usually realize something mattered only after it happened.**

Hindsight continuously keeps only a short rolling window of recent audio on the Pixel Watch.

Older audio disappears automatically.

Nothing is permanently stored unless the user decides a moment was worth keeping.

Then the user taps the watch.

Hindsight preserves the previous few minutes.

The important idea is:

**You don't start recording. You save what already happened.**

The entire website should be built around making that concept emotionally and visually obvious.

## Website Philosophy

Do not build a conventional SaaS landing page.

Do not start with:

- logo
- headline
- paragraph
- two buttons
- three feature cards

Instead, create a cinematic scroll narrative.

Think of the storytelling quality of Apple's best hardware pages, but do not copy Apple visually.

Borrow the principles:

- product is the protagonist
- one idea per viewport
- dramatic typography
- obsessive spacing
- highly controlled motion
- scroll becomes a timeline
- interaction explains the product
- physical device and software behave as one object
- minimal text
- long visual pauses
- transitions instead of separate boxed sections

The experience should feel closer to an interactive product film than a landing page.

## Primary Hero

The main object is a **Pixel Watch running the real Hindsight UI**.

Do not draw a generic watch face.

Inspect the actual Wear OS interface from the repository.

Recreate that interface in HTML / React / SVG / Canvas.

The watch body can be constructed using:

- CSS
- SVG
- layered image assets
- WebGL if genuinely beneficial

But the app screen itself should ideally be live UI.

This lets the website animate actual application states during scrolling.

Examples:

- waveform changes
- buffer time changes
- recording state changes
- saved state
- transfer state
- success state

The watch UI should be visually indistinguishable from the real Hindsight app.

## Device Component Architecture

Create reusable presentation components such as:

- `PixelWatch`
- `WatchScreen`
- `WatchWaveform`
- `WatchBuffer`
- `WatchSaveState`
- `PixelPhone`
- `PhoneTimeline`
- `RecordingClip`
- `TranscriptSearch`

These names are illustrative.

If equivalent components already exist in the app repository, adapt or reuse their logic and styles.

Do not duplicate existing logic unnecessarily.

Separate:

- product UI state
- cinematic camera / scroll state

The actual UI components should remain deterministic and reusable.

GSAP controls how those states are revealed.

## Cinematic Scroll Story

The full site should behave like one continuous narrative.

### Opening

Black screen.

Nothing visible.

Very small text appears:

**You only know a moment mattered...**

Scroll.

The sentence slowly moves upward.

Then:

**...after it happened.**

Silence.

The text fades.

A reflection appears in the darkness.

Slowly reveal the outline of a Pixel Watch.

The watch display wakes up.

The actual Hindsight waveform is alive.

No buttons yet.

Only:

**HINDSIGHT**

and eventually:

**Remember what just happened.**

The opening should feel expensive and restrained.

### The Watch Becomes the Page

As scrolling begins, the Pixel Watch moves toward the camera.

It becomes very large.

Eventually the display nearly fills the viewport.

Because the UI is recreated with real components, the transition should move seamlessly from:

physical watch

to

actual application UI.

The visitor should momentarily forget they are looking at a device mockup.

### Always Listening

Show the real Hindsight waveform running.

Headline:

**Always listening.**

Scroll.

Older waveform data begins moving away.

Headline changes:

**Never keeping.**

Visualize the actual ring-buffer behavior.

Audio enters.

Time advances.

Old audio falls off the buffer.

Do not explain the ring buffer with a diagram yet.

Demonstrate it.

Show a subtle timestamp progression.

For example:

- NOW
- -00:30
- -01:00
- -02:00
- -05:00

As time progresses, earlier waveform data fades permanently.

Copy:

**Only the last few minutes exist.**

### Human Moment

Now introduce meaning into the waveform.

Use subtle fragments of conversation embedded within time.

For example:

> "There's this tiny restaurant..."

> "Remember the code is..."

> "I think we should..."

The words move backward with time.

One fragment becomes visually important.

The visitor scrolls.

Large type:

**Wait.**

Then:

**That mattered.**

Freeze the animation.

### Tap

The watch becomes physical again.

Tilt it slightly.

Show an extremely restrained tap interaction.

The actual Hindsight UI responds.

Do not use a large cartoon pointer.

Use either:

- a minimal touch ripple
- a faint fingertip silhouette

The previous section of waveform becomes selected.

Everything else remains temporary.

Large text:

**Tap.**

Scroll.

**The past few minutes are saved.**

This should be the moment where the product becomes completely understandable.

### Rewind

Now create the signature sequence of the website.

Scroll direction appears to move time backward.

The waveform moves backward.

Timestamps reverse.

The selected audio window travels toward the present.

Copy appears sequentially:

**You don't start recording.**

Pause.

Then:

**You save what already happened.**

Make this second line enormous.

Treat this as the visual centerpiece of the entire website.

The page should feel almost impossible for a normal marketing site.

### Watch to Phone

Pull out from the watch.

Introduce the Pixel phone.

Again, do not use generic screenshots.

Inspect the real mobile UI from the repository.

Recreate it using React components derived from the application's source.

Watch remains on one side.

Phone appears on the other.

The saved waveform physically travels from the watch into the phone.

Do not draw an arrow.

Make the content itself become the transition.

The clip lands inside the real Hindsight timeline.

The watch confirms transfer.

Then it disappears from watch storage.

Small copy:

**Saved on your phone.**

Then:

**Gone from your watch.**

If retry / confirmation behavior exists in the source code, represent it accurately.

### Phone Timeline

The phone becomes huge.

Push the camera into the display.

Eventually remove the physical phone frame completely.

The website itself becomes the Hindsight timeline.

The actual UI is now full-screen.

Show how clips exist along time rather than merely in a list.

Demonstrate actual interactions.

For example:

- scrubbing
- playback
- jumping in time
- renaming a recording
- searching transcripts
- changing playback speed
- sharing
- deleting
- undo

Only demonstrate features that actually exist in the codebase.

Do not build feature cards.

The interface itself is the feature section.

### Search

If transcript search exists, create a highly polished sequence.

A phrase is typed.

The timeline responds.

Relevant moments appear.

Then erase the search.

Return to the temporal timeline.

Keep everything connected.

### Privacy

Transition back into darkness.

Almost everything disappears.

Huge text:

**Your conversations aren't content.**

Scroll.

**They stay yours.**

Explain the architecture using very little text.

Derive the exact statements from the source code.

Examples might include:

- temporary watch buffer
- explicit save action
- on-device processing
- local storage
- automatic cleanup

Do not state anything that cannot be verified from the implementation.

Do not use shields, locks, security badges, or generic privacy illustrations.

Typography is enough.

### Engineering Section

Now allow the site to become slightly more technical.

Headline:

**Always listening doesn't mean always storing.**

Build a beautiful live visualization of the retention buffer.

Allow the user to move between durations supported by the actual app.

For example:

- 1 min
- 5 min
- 15 min
- 30 min
- 60 min

The waveform visually grows with the selected retention duration.

If exact storage estimates can be derived from the app's audio format, calculate them from the actual implementation rather than hard-coding guesses.

Display:

- duration
- approximate storage
- audio format
- sample rate
- channel count
- codec

Only include values verified in the code.

This section should feel like an engineering notebook embedded inside a product film.

### Interactive Buffer Demo

Create one section where the visitor can interact with the core idea directly.

A waveform continuously enters a rolling buffer.

Provide a small control:

**Retention**
**5 min**

The user can change the retention window.

Old audio visibly disappears.

Then provide a:

**Save last X minutes**

interaction.

The selected waveform becomes permanent.

This demo should reuse the same state model as the cinematic watch demo if practical.

It must not feel like a separate toy.

### GitHub / Open Source

Since the product is open source, make this part of the design rather than hiding it in the footer.

Create a restrained transition:

**Want to know how it works?**

Then reveal a simplified architecture derived from the repository.

Potential structure:

```text
Pixel Watch
↓
rolling audio buffer
↓
save event
↓
transfer
↓
phone archive
↓
timeline / transcription
```

Use the real implementation architecture.

Include a link:

**Read the source →**

to the GitHub repository.

Do not turn this into a giant technical architecture diagram.

Keep it elegant.

## Final Sequence

Remove the architecture.

Remove the phone.

Remove everything.

Return to the Pixel Watch.

It floats in darkness.

The live Hindsight waveform continues moving.

Small:

**HINDSIGHT**

Huge:

**Remember what just happened.**

Supporting line:

**Because you shouldn't have to know a moment matters before it happens.**

Primary CTA:

**View on GitHub**

Secondary CTA:

**How it works**

Finish with almost nothing.

## Product UI Rule

This is extremely important:

Do NOT manually approximate Hindsight's UI when the source code already contains the answer.

Inspect the application code.

If possible:

- reuse styles
- reuse icons
- reuse SVGs
- reuse waveform algorithms
- reuse state transitions
- reuse strings
- reuse design tokens
- reuse typography
- reuse relevant React logic

Convert platform-native UI into web equivalents when direct reuse is impossible.

The website should essentially contain a web-rendered simulation of the actual app.

The result should survive changes in the product better than a collection of static screenshots would.

## Scroll Implementation

Use GSAP ScrollTrigger.

Build scroll behavior using timelines tied to normalized scroll progress.

Favor transforms and opacity.

Avoid triggering React re-renders on every scroll frame.

GSAP should mutate presentation properties directly where appropriate.

For complex sections:

1. pin the scene
2. map scroll progress to a finite animation timeline
3. unpin naturally afterward

The user must always retain physical control over scrolling.

No forced snap scrolling unless it produces a clearly better result.

Scrolling backward must reverse the story naturally.

## Astro Architecture

Prefer a structure such as:

```text
src/
  components/
    devices/
    hindsight/
    cinematic/
    ui/
  sections/
    Hero.astro
    Buffer.astro
    Moment.astro
    Save.astro
    Transfer.astro
    Timeline.astro
    Privacy.astro
    Engineering.astro
    Finale.astro
  scripts/
    animation/
  styles/
```

Use Astro components for mostly-static sections.

Use React islands only where live state or interaction is necessary.

Do not hydrate the entire website.

Example:

```astro
<WatchDemo client:visible />
```

rather than turning the whole page into a React application.

## Performance

The site should feel instant.

Avoid unnecessary WebGL.

Prefer:

- CSS transforms
- SVG
- Canvas
- `requestAnimationFrame`
- GSAP

Use WebGL only for effects that cannot be achieved cleanly otherwise.

Optimize for mobile GPU performance.

Avoid huge videos if the same experience can be rendered procedurally.

Do not ship a prerecorded animation of the app if actual UI components can produce it.

Target:

- Lighthouse Performance > 90
- Accessibility > 95
- Best Practices > 95
- SEO > 95

No layout shift.

Responsive images.

Lazy loading.

Code-split React islands.

Respect `prefers-reduced-motion`.

## Responsive Design

Mobile is not a compressed desktop version.

Design a separate cinematic composition for narrow screens.

On mobile:

- the watch should nearly fill the viewport
- use less device rotation
- reduce depth effects
- keep the core scroll timeline
- use fewer simultaneous layers
- make typography extremely deliberate
- keep the actual UI readable
- ensure scroll performance stays excellent

The site should look particularly good on a Pixel phone.

## Navigation

Do not show a traditional navbar immediately.

Allow the opening scene to breathe.

After the visitor has entered the story, reveal a minimal navigation bar containing:

- Hindsight
- How it works
- Privacy
- GitHub

Keep it understated.

## Design Rules

Avoid:

- generic SaaS layouts
- feature grids
- Bento cards
- purple gradients
- glowing blobs
- excessive glassmorphism
- rounded cards everywhere
- fake testimonials
- fake statistics
- stock photos
- marketing illustrations
- huge collections of badges
- busy navigation
- excessive iconography

The site should be:

- quiet
- cinematic
- precise
- technical
- human
- slightly mysterious
- minimal
- confident

## Most Important Principle

Whenever there are two choices:

**explain it**
or
**show it**

show it.

Whenever there are two choices:

**add another visual**
or
**give the current visual more space**

give it space.

Whenever there are two choices:

**fake the app**
or
**derive it from the source code**

derive it from the source code.

The final website should make the visitor experience the central idea:

Something happens.

You don't know it's important.

Time continues.

You realize it mattered.

You tap your watch.

Hindsight reaches backward.

The moment comes back.

The entire experience should culminate in one sentence:

**Hindsight doesn't record your life. It gives you a few minutes to change your mind.**
