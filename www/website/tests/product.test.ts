import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { advanceBuffer, AUDIO, changeRetention, edgeFalloff, formatTime, frameSeconds, initialBuffer, perceptualLevel, RETENTIONS, saveBuffer, storageMiB, WaveformMotion, watchFeedback } from '../src/lib/product';

test('native retention options, default, and frame-aligned duration', () => {
  assert.deepEqual(RETENTIONS, [1, 5, 15, 30, 60]);
  assert.equal(initialBuffer().retention, 1);
  assert.equal(formatTime(59.968), '1:00');
  assert.equal(frameSeconds, .064);
  assert.equal(formatTime(-20), '0:00');
});
test('buffer evicts old samples, not explicitly saved windows', () => {
  let state = saveBuffer(initialBuffer());
  const saved = structuredClone(state.saved[0]);
  for (let i = 0; i < 100; i++) state = advanceBuffer(state, 5);
  assert.ok(state.samples.every(s => s.time > state.now - 60));
  assert.deepEqual(state.saved[0], saved);
  assert.equal(state.saved[0].end - state.saved[0].start, 60);
  const latest = saveBuffer(state).saved.at(-1)!;
  assert.equal(latest.end - latest.start, 60, 'coarse UI clock steps still save the whole window');
  assert.equal(advanceBuffer(state, -1), state);
});
test('shrinking evicts immediately; growing does not resurrect forgotten audio', () => {
  let state = changeRetention(initialBuffer(), 5);
  for (let i = 0; i < 60; i++) state = advanceBuffer(state, 5);
  assert.equal(state.samples.length, 300);
  const shrunk = changeRetention(state, 1);
  assert.ok(shrunk.samples.every(s => s.time > state.now - 60));
  assert.deepEqual(changeRetention(shrunk, 60).samples, shrunk.samples);
});
test('save snapshots are separate, bounded demo history does not mutate snapshots', () => {
  let state = initialBuffer();
  for (let i = 0; i < 4; i++) state = saveBuffer(advanceBuffer(state));
  assert.equal(state.saved.length, 3);
  assert.deepEqual(state.saved.map(c => c.id), [2, 3, 4]);
  assert.notEqual(state.saved[2].samples[0], state.samples[0]);
});
test('storage derives from codec payload and one segment, not PCM', () => {
  assert.equal(storageMiB(1).toFixed(2), '0.26');
  assert.equal(storageMiB(60).toFixed(2), '10.39');
  assert.ok(Math.abs(storageMiB(5) - storageMiB(1) - 4 * 60 * AUDIO.bitRate / 8 / 1024 ** 2) < 1e-12);
});
test('watch metering noise gate, logarithmic scale, round-display taper', () => {
  assert.equal(perceptualLevel(0), 0);
  assert.equal(perceptualLevel(.0004), 0);
  assert.equal(perceptualLevel(Number.NaN), 0);
  assert.ok(perceptualLevel(.06) > .5);
  assert.equal(perceptualLevel(2), 1);
  assert.equal(edgeFalloff(15), 1);
  assert.equal(edgeFalloff(0), .65);
  assert.equal(edgeFalloff(30), .65);
});
test('native attack/release interpolation settles in silence', () => {
  const motion = new WaveformMotion();
  motion.push(.8);
  motion.advance(1 / 60);
  assert.ok(motion.levels[30] > 0 && motion.levels[30] < .8);
  for (let i = 0; i < 31; i++) motion.push(0);
  for (let i = 0; i < 200; i++) motion.advance(1 / 60);
  assert.ok(motion.levels.every(x => x === 0));
});
test('phone acknowledgement is distinct from a local save', () => {
  assert.equal(watchFeedback('queued'), 'Saved 1:00 · waiting for phone');
  assert.equal(watchFeedback('sending'), '1 pending · awaiting confirmation');
  assert.equal(watchFeedback('confirmed'), 'Saved 1:00 · on phone');
});
const nativePath = new URL('../../../shared/src/main/java/earth/diego/hindsight/shared/WearProtocol.kt', import.meta.url);
test('web constants still match the native source when in the monorepo', { skip: !existsSync(nativePath) }, () => {
  const native = readFileSync(nativePath, 'utf8');
  for (const [key, value] of Object.entries({ SAMPLE_RATE: AUDIO.sampleRate, CHANNEL_COUNT: AUDIO.channels, BIT_RATE: AUDIO.bitRate, SAMPLES_PER_FRAME: AUDIO.samplesPerFrame, FRAMES_PER_SEGMENT: AUDIO.framesPerSegment })) {
    const match = native.match(new RegExp(`const val ${key} = ([\\d_]+)`));
    assert.equal(Number(match?.[1].replaceAll('_', '')), value, `${key} drifted from native source`);
  }
  const settings = readFileSync(new URL('../../../wear/src/main/java/earth/diego/hindsight/data/RecorderSettings.kt', import.meta.url), 'utf8');
  assert.deepEqual([...settings.matchAll(/\w+\((\d+), "\d+ min"\)/g)].map(m => Number(m[1])), [...RETENTIONS]);
});
