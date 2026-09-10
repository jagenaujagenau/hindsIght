// Run with npm run test:browser. Uses an isolated agent-browser session.
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, extname } from 'node:path';
import { promisify } from 'node:util';
import test from 'node:test';

const exec = promisify(execFile);
const dist = new URL('../dist/', import.meta.url);
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.woff2': 'font/woff2' };

test('the opening stays stable while the animation bundle loads', { timeout: 90_000 }, async () => {
  const dir = await mkdtemp(join(tmpdir(), 'hindsight-first-load-'));
  const session = `hindsight-load-${process.pid}`;
  const browser = async (...args) => {
    const { stdout } = await exec('npx', ['--yes', 'agent-browser', '--session', session, ...args]);
    return stdout;
  };
  const server = createServer(async (req, res) => {
    try {
      const url = new URL(req.url, 'http://localhost');
      const path = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
      let body = await readFile(new URL(path, dist));
      // Make the pre-animation render deterministic, even on a warm local cache.
      if (path.includes('index.astro_astro_type_script')) {
        const delay = req.headers.referer?.includes('late') ? 4500 : 600;
        await new Promise(resolve => setTimeout(resolve, delay));
      }
      if (url.searchParams.has('nojs') && path === 'index.html') {
        body = Buffer.from(body.toString().replace(/<script\b[^>]*>[\s\S]*?<\/script>/g, ''));
      }
      res.writeHead(200, { 'Content-Type': types[extname(path)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
      res.end(body);
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}/`;
  const init = join(dir, 'frames.js');
  await writeFile(init, `
    window.__loadFrames = [];
    function sample() {
      const watch = document.querySelector('.hero-device');
      const copy = document.querySelector('.opening-copy > p:first-child');
      if (watch && copy) window.__loadFrames.push({
        ready: document.documentElement.dataset.motion === 'on',
        watch: getComputedStyle(watch).opacity,
        copy: getComputedStyle(copy).opacity,
        copyTop: Math.round(copy.getBoundingClientRect().top)
      });
      if (performance.now() < 2400) requestAnimationFrame(sample);
      else window.__loadDone = true;
    }
    requestAnimationFrame(sample);
  `);
  async function assertStableOpening() {
    await browser('wait', '--fn', 'window.__loadDone === true');
    const frames = JSON.parse(await browser('eval', 'window.__loadFrames'));
    assert.ok(frames.some(frame => !frame.ready), 'must observe frames before the bundle initializes');
    assert.ok(frames.some(frame => frame.ready), 'animation must initialize');
    assert.ok(frames.every(frame => frame.watch === '0'), 'watch must not flash before its scroll reveal');
    assert.ok(frames.every(frame => frame.copy === '1'), 'opening text must not disappear on initialization');
    assert.ok(Math.max(...frames.map(f => f.copyTop)) - Math.min(...frames.map(f => f.copyTop)) < 8, 'opening composition must not jump');
  }
  try {
    await browser('--init-script', init, 'open', url);
    await assertStableOpening();
    await browser('set', 'viewport', '390', '844');
    await browser('open', url);
    await assertStableOpening();

    await browser('scroll', 'down', '1500');
    await browser('wait', '--fn', 'Number(getComputedStyle(document.querySelector(".hero-device")).opacity) > .9');
    await browser('click', '#motion-control');
    assert.equal(JSON.parse(await browser('eval', 'getComputedStyle(document.querySelector(".hero-device")).opacity')), '1', 'motion off restores the static watch');

    await browser('open', `${url}?nojs`);
    assert.equal(JSON.parse(await browser('eval', 'getComputedStyle(document.querySelector(".hero-device")).opacity')), '1', 'no-JS content remains visible');

    await browser('open', `${url}?late`);
    await browser('wait', '--fn', 'document.documentElement.dataset.motion === "off"');
    assert.equal(JSON.parse(await browser('eval', 'getComputedStyle(document.querySelector(".hero-device")).opacity')), '1', 'late bundle must not hide the static fallback');

    await browser('set', 'media', 'dark', 'reduced-motion');
    await browser('open', url);
    await browser('wait', '--fn', 'document.documentElement.dataset.motion === "off"');
    assert.equal(JSON.parse(await browser('eval', 'document.documentElement.classList.contains("cinematic")')), false, 'reduced motion uses the static layout');
    assert.equal(JSON.parse(await browser('eval', 'getComputedStyle(document.querySelector(".hero-device")).opacity')), '1', 'reduced-motion watch stays visible');
  } finally {
    await browser('close');
    await new Promise(resolve => server.close(resolve));
    await rm(dir, { recursive: true, force: true });
  }
});
