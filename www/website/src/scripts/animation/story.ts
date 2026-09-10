import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { edgeFalloff, perceptualLevel, samplePeak, WaveformMotion, watchFeedback } from '../../lib/product';

gsap.registerPlugin(ScrollTrigger);
const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const narrow = matchMedia('(max-width: 600px)');
const root = document.documentElement;
const motionButton = document.querySelector<HTMLButtonElement>('#motion-control');
// If startup timed out, don't swap the now-visible static story out from under the reader.
let manualOff = root.dataset.motion === 'fallback';
let context: gsap.Context | undefined;
let stopWaves = () => {};

function liveWaves() {
  const waves = [...document.querySelectorAll<SVGSVGElement>('[data-live-wave]')].map(svg => {
    const motion = new WaveformMotion();
    for (let i = 0; i < 31; i++) motion.push(perceptualLevel(samplePeak(i)));
    motion.advance(0.1);
    return { svg, bars: [...svg.querySelectorAll<SVGRectElement>('[data-bar]')], motion, visible: false };
  });
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      const wave = waves.find(w => w.svg === entry.target);
      if (wave) wave.visible = entry.isIntersecting;
    }
    schedule();
  });
  let frame = 0;
  let previous = 0;
  let lastSample = 0;
  let index = 31;
  function schedule() {
    if (!frame && !document.hidden && waves.some(w => w.visible)) frame = requestAnimationFrame(draw);
  }
  function draw(time: number) {
    frame = 0;
    if (document.hidden || !waves.some(w => w.visible)) { previous = 0; return; }
    const delta = previous ? Math.min((time - previous) / 1000, 0.1) : 1 / 60;
    previous = time;
    const push = time - lastSample >= 64;
    if (push) { index++; lastSample = time; }
    for (const wave of waves) {
      if (!wave.visible) continue;
      if (push) wave.motion.push(perceptualLevel(samplePeak(index)));
      wave.motion.advance(delta);
      for (let i = 0; i < wave.bars.length; i++) {
        const half = Math.max(600 / 61 / 2, wave.motion.levels[i] * 90 * edgeFalloff(i));
        wave.bars[i].setAttribute('y', (90 - half).toFixed(2));
        wave.bars[i].setAttribute('height', (half * 2).toFixed(2));
      }
    }
    schedule();
  }
  waves.forEach(w => observer.observe(w.svg));
  const visibility = () => { previous = 0; schedule(); };
  document.addEventListener('visibilitychange', visibility);
  return () => { observer.disconnect(); cancelAnimationFrame(frame); document.removeEventListener('visibilitychange', visibility); };
}

function build() {
  context?.revert();
  stopWaves();
  root.classList.remove('cinematic');
  const off = manualOff || reduced.matches;
  root.dataset.motion = off ? 'off' : 'on';
  motionButton?.setAttribute('aria-pressed', String(off));
  motionButton?.setAttribute('aria-label', reduced.matches ? 'Motion off: system reduced-motion preference' : off ? 'Enable cinematic motion' : 'Turn off cinematic motion');
  if (motionButton) motionButton.disabled = reduced.matches;
  const playIcon = motionButton?.querySelector<HTMLElement>('[data-motion-play]');
  const pauseIcon = motionButton?.querySelector<HTMLElement>('[data-motion-pause]');
  if (playIcon) playIcon.hidden = !off;
  if (pauseIcon) pauseIcon.hidden = off;
  document.dispatchEvent(new CustomEvent('hindsight:motion', { detail: { off } }));
  if (off) {
    root.classList.remove('motion-pending');
    return;
  }
  root.classList.add('cinematic');
  const mobile = narrow.matches;
  context = gsap.context(() => {
    ScrollTrigger.create({ trigger: '.hero', start: 'top top', end: 'bottom top', onUpdate: self => document.querySelector('.site-nav')?.classList.toggle('entered', self.progress > .25), onLeave: () => document.querySelector('.site-nav')?.classList.add('entered'), onLeaveBack: () => document.querySelector('.site-nav')?.classList.remove('entered') });
    const hero = gsap.timeline({ scrollTrigger: { trigger: '.hero', pin: '.hero-stage', start: 'top top', end: '+=220%', scrub: .6, invalidateOnRefresh: true } });
    hero.fromTo('.opening-copy>p:first-child', { y: 0 }, { y: -8, duration: .2 }, 0)
      .fromTo('.opening-after', { opacity: 0, y: 35 }, { opacity: 1, y: 0, duration: .8 }, .3)
      .to('.opening-copy', { y: -70, opacity: 0, duration: .7 }, 1.3)
      .fromTo('.hero-device', { opacity: 0, y: 90, rotation: -22, scale: mobile ? .65 : .8 }, { opacity: 1, y: 0, rotation: mobile ? -7 : -13, scale: mobile ? .87 : 1, duration: 1.5 }, 1.6)
      .fromTo('.hero-wordmark', { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: 1.3 }, 2.2)
      .fromTo('.hero-caption', { opacity: 0, y: 30 }, { opacity: 1, y: 0, duration: .8 }, 2.5)
      .fromTo('.hero-annotation', { opacity: 0 }, { opacity: 1, duration: .8 }, 2.7)
      .to({}, { duration: .8 });
    const story = gsap.timeline({ scrollTrigger: { trigger: '.story', pin: '.story-stage', start: 'top top', end: '+=460%', scrub: .7, invalidateOnRefresh: true } });
    gsap.set(['.story-forget', '.story-moment', '.story-tap', '.story-stream'], { autoAlpha: 0 });
    story.fromTo('.story-device', { rotation: -10 }, { rotation: 0, duration: 1 }, 0)
      .to('.story-device', { scale: mobile ? 1.7 : 2.8, xPercent: mobile ? 0 : -60, opacity: .15, duration: 1 }, 1)
      .to('.story-listen', { autoAlpha: 0, y: -40, duration: .5 }, 1)
      .to('.story-forget', { autoAlpha: 1, duration: .7 }, 1.5)
      .to('.story-stream', { autoAlpha: 1, duration: .7 }, 1.5)
      .fromTo('.stream-wave', { xPercent: 0 }, { xPercent: -20, duration: 2.5, ease: 'none' }, 1.5)
      .fromTo('.stream-quotes', { xPercent: 15 }, { xPercent: -30, duration: 2.5, ease: 'none' }, 1.5)
      .to('.story-forget', { autoAlpha: 0, y: -25, duration: .4 }, 3)
      .to('.story-moment', { autoAlpha: 1, duration: .6 }, 3.5)
      .to('.saved-selection', { opacity: 1, duration: .5 }, 4)
      .to('.story-moment', { autoAlpha: 0, y: -25, duration: .5 }, 5)
      .to('.story-stream', { autoAlpha: 0, duration: .5 }, 5.1)
      .to('.story-device', { scale: 1, xPercent: 0, opacity: 1, rotation: mobile ? -4 : -10, duration: 1 }, 5.2)
      .to('.story-tap', { autoAlpha: 1, duration: .6 }, 5.7)
      .fromTo('.story-device .tap-ripple', { scale: .2, opacity: 0 }, { scale: 1.8, opacity: .5, duration: .4 }, 6.1)
      .to('.story-device .tap-ripple', { opacity: 0, duration: .4 }, 6.5)
      .to({}, { duration: 1 });
    const storyFeedback = document.querySelector<HTMLElement>('.story-device [data-watch-feedback]');
    story.eventCallback('onUpdate', () => { if (storyFeedback) storyFeedback.textContent = watchFeedback(story.time() > 6.5 ? 'queued' : story.time() > 6.1 ? 'saving' : 'listening'); });
    const rewind = gsap.timeline({ scrollTrigger: { trigger: '.rewind', pin: '.rewind-stage', start: 'top top', end: '+=180%', scrub: .7 } });
    rewind.fromTo('.rewind h2', { opacity: .7, y: 50 }, { opacity: 1, y: 0, duration: 1.5 }, .5)
      .to('.rewind-before', { y: -15, duration: 1 }, .7)
      .fromTo('.rewind-track', { xPercent: -10 }, { xPercent: 10, duration: 3, ease: 'none' }, 0);
    const reverseTime = document.querySelector('.reverse-time');
    rewind.eventCallback('onUpdate', () => { if (reverseTime) reverseTime.textContent = `−00:${String(Math.round(rewind.progress() * 59)).padStart(2, '0')}`; });
    const transfer = gsap.timeline({ scrollTrigger: { trigger: '.transfer', pin: '.transfer-stage', start: 'top top', end: '+=200%', scrub: .7 } });
    transfer.fromTo('.transfer-phone', { autoAlpha: 0, y: 80 }, { autoAlpha: 1, y: 0, duration: 1 }, 0)
      .fromTo('.travelling-clip', { x: mobile ? -65 : -230, opacity: 0, scale: .7 }, { opacity: 1, duration: .3 }, .5)
      .to('.travelling-clip', { x: mobile ? 70 : 230, y: mobile ? -55 : -90, scale: 1, duration: 1.3, ease: 'power2.inOut' }, .8)
      .to('.travelling-clip', { opacity: 0, duration: .25 }, 2.1)
      .to('.transfer-watch', { opacity: .2, duration: .6 }, 2.7)
      .to('.transfer-phone', { scale: mobile ? .8 : 1.05, rotation: 0, duration: 1 }, 2.7)
      .to('.transfer-watch', { opacity: 0, duration: .5 }, 3.8)
      .to('.transfer-heading', { opacity: 0, y: -30, duration: .7 }, 3.8)
      .to('.transfer-phone', { scale: mobile ? 2 : 5.8, x: mobile ? -80 : -250, y: mobile ? -30 : 80, duration: 1.5, ease: 'power2.in' }, 4)
      .to('.transfer-phone .pixel-phone', { borderColor: '#0c0f14', boxShadow: 'none', duration: 1 }, 4)
      .to('.transfer-note', { opacity: 0, duration: .4 }, 4.2);
    const transferFeedback = document.querySelector<HTMLElement>('.transfer-watch [data-watch-feedback]');
    transfer.eventCallback('onUpdate', () => { if (transferFeedback) transferFeedback.textContent = watchFeedback(transfer.time() > 2.5 ? 'confirmed' : transfer.time() > .8 ? 'sending' : 'queued'); });
    gsap.fromTo('.privacy h2 em', { opacity: .8 }, { opacity: 1, scrollTrigger: { trigger: '.privacy', start: 'top 55%', end: 'center 45%', scrub: true } });
    gsap.fromTo('.final-watch', { y: 55, opacity: .3 }, { y: 0, opacity: 1, scrollTrigger: { trigger: '.finale', start: 'top 85%', end: 'top 10%', scrub: 1 } });
  });
  stopWaves = liveWaves();
  ScrollTrigger.refresh();
  // The opening copy is already visible; fading it from zero here causes a second flash.
  root.classList.remove('motion-pending');
}

motionButton?.addEventListener('click', () => {
  // The system preference always wins; users can disable motion independently.
  manualOff = !manualOff;
  build();
});
reduced.addEventListener('change', build);
narrow.addEventListener('change', build);
build();
document.fonts.ready.then(() => ScrollTrigger.refresh());
// Optional technical notes change section heights without moving the reader.
document.addEventListener('toggle', event => {
  if (event.target instanceof HTMLDetailsElement) ScrollTrigger.refresh();
}, true);
window.addEventListener('pagehide', () => { stopWaves(); context?.revert(); });
window.addEventListener('pageshow', event => { if (event.persisted) build(); });
