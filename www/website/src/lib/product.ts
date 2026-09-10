/** Web ports of the native product. Source mapping: docs/product-source.md. */
export const SOURCE = 'https://github.com/jagenaujagenau/hindsIght';
export const RETENTIONS = [1, 5, 15, 30, 60] as const;
export type Retention = (typeof RETENTIONS)[number];
export const AUDIO = { sampleRate: 16000, channels: 1, bitRate: 24000, samplesPerFrame: 1024, framesPerSegment: 469 };
export const frameSeconds = AUDIO.samplesPerFrame / AUDIO.sampleRate;
export function storageMiB(minutes: number) {
  return (minutes * 60 + AUDIO.framesPerSegment * frameSeconds) * AUDIO.bitRate / 8 / 1024 ** 2;
}
export function formatTime(seconds: number) {
  const rounded = Math.max(0, Math.round(seconds));
  return `${Math.floor(rounded / 60)}:${String(rounded % 60).padStart(2, '0')}`;
}
export function perceptualLevel(peak: number, rangeDb = 60) {
  if (!Number.isFinite(peak) || peak <= 0.0005) return 0;
  const level = Math.max(0, Math.min(1, (20 * Math.log10(peak) + rangeDb) / rangeDb));
  return level < 0.12 ? 0 : level;
}
export function edgeFalloff(index: number, count = 31) {
  const half = (count - 1) / 2;
  return 1 - ((index - half) / half) ** 2 * 0.35;
}
/** Deterministic fictional speech envelope, NOT captured microphone data. */
export function samplePeak(index: number) {
  const envelope = Math.abs(Math.sin(index * 0.37) * Math.cos(index * 0.13));
  return index % 39 > 30 ? 0 : envelope * 0.12;
}
export class WaveformMotion {
  levels = Array<number>(31).fill(0);
  private targets = Array<number>(31).fill(0);
  push(level: number) {
    this.targets.shift();
    this.targets.push(Number.isFinite(level) ? Math.max(0, Math.min(1, level)) : 0);
  }
  advance(seconds: number) {
    if (!Number.isFinite(seconds) || seconds <= 0) return;
    const dt = Math.min(seconds, 0.1);
    for (let i = 0; i < 31; i++) {
      const target = this.targets[i];
      const response = 1 - Math.exp(-dt / (target > this.levels[i] ? 0.035 : 0.12));
      const next = this.levels[i] + (target - this.levels[i]) * response;
      this.levels[i] = Math.abs(next - target) < 0.001 ? target : next;
    }
  }
}
export type Sample = { time: number; peak: number };
export type SavedWindow = { id: number; start: number; end: number; samples: Sample[] };
export type BufferState = { now: number; retention: Retention; samples: Sample[]; saved: SavedWindow[] };
export function initialBuffer(retention: Retention = 1): BufferState {
  return { now: 60, retention, samples: Array.from({ length: 60 }, (_, i) => ({ time: i + 1, peak: perceptualLevel(samplePeak(i)) })), saved: [] };
}
export function advanceBuffer(state: BufferState, seconds = 1): BufferState {
  if (!Number.isSafeInteger(seconds) || seconds <= 0) return state;
  const now = state.now + seconds;
  const cutoff = now - state.retention * 60;
  const samples = state.samples.filter(s => s.time > cutoff);
  // Keep one sample per simulated second even when the UI advances in large steps.
  // Otherwise a 5-second clock tick would make a full 60-second save appear 56s long.
  for (let time = Math.max(state.now + 1, cutoff + 1); time <= now; time++) {
    samples.push({ time, peak: perceptualLevel(samplePeak(time)) });
  }
  return { ...state, now, samples };
}
export function changeRetention(state: BufferState, retention: Retention): BufferState {
  return { ...state, retention, samples: state.samples.filter(s => s.time > state.now - retention * 60) };
}
export function saveBuffer(state: BufferState): BufferState {
  if (!state.samples.length) return state;
  const clip = { id: (state.saved.at(-1)?.id ?? 0) + 1, start: state.samples[0].time - 1, end: state.now, samples: state.samples.map(s => ({ ...s })) };
  return { ...state, saved: [...state.saved, clip].slice(-3) };
}
export type SavePhase = 'listening' | 'saving' | 'queued' | 'sending' | 'confirmed';
export function watchFeedback(phase: SavePhase, duration = 60) {
  switch (phase) {
    case 'saving': return 'Saving…';
    case 'queued': return `Saved ${formatTime(duration)} · waiting for phone`;
    case 'sending': return '1 pending · awaiting confirmation';
    case 'confirmed': return `Saved ${formatTime(duration)} · on phone`;
    default: return 'Hold to stop · swipe for settings';
  }
}
