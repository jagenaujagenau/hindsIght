import { useEffect, useRef, useState } from 'react';
import { advanceBuffer, changeRetention, formatTime, initialBuffer, RETENTIONS, saveBuffer, storageMiB, type Retention } from '../../lib/product';
import Waveform from './Waveform';
import PixelWatch from '../devices/PixelWatch';
import Icon from '../ui/Icon';

export default function BufferDemo() {
  const [state, setState] = useState(() => initialBuffer());
  const [running, setRunning] = useState(true);
  const [savedAt, setSavedAt] = useState(0);
  const [visible, setVisible] = useState(false);
  const [message, setMessage] = useState('Only what you choose to keep.');
  const root = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const media = matchMedia('(prefers-reduced-motion: reduce)');
    if (media.matches || document.documentElement.dataset.motion === 'off') setRunning(false);
    const changed = () => { if (media.matches) setRunning(false); };
    media.addEventListener('change', changed);
    const motionChanged = () => { if (document.documentElement.dataset.motion === 'off') setRunning(false); };
    document.addEventListener('hindsight:motion', motionChanged);
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting));
    if (root.current) observer.observe(root.current);
    return () => { observer.disconnect(); media.removeEventListener('change', changed); document.removeEventListener('hindsight:motion', motionChanged); };
  }, []);
  useEffect(() => {
    if (!running || !visible) return;
    const timer = setInterval(() => { if (!document.hidden) setState(s => advanceBuffer(s, 5)); }, 250);
    return () => clearInterval(timer);
  }, [running, visible]);
  useEffect(() => {
    if (!savedAt) return;
    const timer = setTimeout(() => setSavedAt(0), 5000);
    return () => clearTimeout(timer);
  }, [savedAt]);
  const save = () => {
    setState(s => saveBuffer(s));
    setSavedAt(Date.now());
    setMessage(`Saved ${formatTime(state.now - (state.samples[0]?.time ?? state.now) + 1)} of sample audio. This part stays, even as the rest slips away.`);
  };
  const latestSave = state.saved.at(-1);
  const duration = Math.min(state.retention * 60, state.now - (state.samples[0]?.time ?? state.now) + 1);
  return <div className="buffer-demo" ref={root}>
    <div className="engineering-watch"><PixelWatch phase={savedAt ? 'queued' : 'listening'} savedDuration={latestSave ? latestSave.end - latestSave.start : duration} retention={state.retention} buffered={duration} onSave={save} live={false} peaks={state.samples.slice(-31).map(s => s.peak)} /></div>
    <div className="buffer-instrument">
      <div className="instrument-top"><span className="eyebrow">How far back?</span><span className="mono">{formatTime(duration)} within reach</span></div>
      <fieldset className="retention-options"><legend className="sr-only">How many minutes to remember</legend>{RETENTIONS.map(minutes => <label key={minutes}><input type="radio" name="retention" value={minutes} checked={state.retention === minutes} onChange={() => setState(s => changeRetention(s, minutes as Retention))} /><span>{minutes} min</span></label>)}</fieldset>
      <div className="buffer-plot"><div className="retained-wave" style={{ width: `${25 + state.retention / 60 * 75}%` }}><Waveform count={96} peaks={state.samples.map(s => s.peak)} /></div><span className="now-marker" /></div>
      <div className="axis-labels mono"><span>−{formatTime(state.retention * 60)} · forgotten beyond here</span><span>NOW</span></div>
      <div className="buffer-actions"><button className="button primary" onClick={save}>{`Save last ${state.retention} ${state.retention === 1 ? 'minute' : 'minutes'}`} <Icon name="rewind" /></button><button className="text-button" onClick={() => setRunning(r => !r)}>{running ? 'Pause' : 'Resume'}</button><button className="text-button" onClick={() => setState(s => advanceBuffer(s, 30))}>+30 sec</button></div>
      <p className="demo-caption">Just a demo. Time moves 20 times faster here. This page never uses your microphone.</p>
      <p className="save-announcement" role="status">{message}</p>
      <div className="saved-windows">{state.saved.map(clip => <div className="saved-window" key={clip.id}><span className="mono">KEPT {String(clip.id).padStart(2, '0')}</span><Waveform count={40} peaks={clip.samples.map(s => s.peak)} progress={1} /><span className="mono">{formatTime(clip.end - clip.start)}</span></div>)}</div>
      <details className="technical-details"><summary>The small-print version <Icon name="arrow-down" /></summary>
      <dl className="audio-spec"><div><dt>Approx. buffer</dt><dd>{storageMiB(state.retention).toFixed(2)} <small>MiB</small></dd></div><div><dt>Sample rate</dt><dd>16 <small>kHz</small></dd></div><div><dt>Encoding</dt><dd>AAC-LC</dd></div><div><dt>Channels / rate</dt><dd>Mono <small>/ 24 kbps</small></dd></div></dl>
      <p className="demo-caption">Encoded payload + one ~30-second segment. Container overhead and saved clips are extra. Saves remux to .m4a; no re-encoding.</p>
      </details>
    </div>
  </div>;
}
