import { useEffect, useRef, useState } from 'react';
import Waveform from './Waveform';
import { formatTime } from '../../lib/product';
import Icon from '../ui/Icon';

type Clip = { id: number; title: string; time: string; hour: string; duration: number; transcript: string; seed: number };
const SAMPLES: Clip[] = [
  { id: 1, title: 'The little restaurant', time: '17:02', hour: '17', duration: 60, transcript: 'There’s this tiny restaurant on the corner. No sign outside. Ask for the table by the window. We should go on Sunday.', seed: 8 },
  { id: 2, title: 'A thought on the walk', time: '14:18', hour: '14', duration: 60, transcript: 'I think we should take the long way home. There’s something about having nowhere to be.', seed: 30 },
  { id: 3, title: 'Mum’s secret ingredient', time: '09:41', hour: '09', duration: 60, transcript: 'The secret is a little lemon at the end. Your grandad always said it needed something, but he never guessed what it was.', seed: 70 },
];
export function TimelineRows({ clips = SAMPLES, onOpen }: { clips?: Clip[]; onOpen?: (clip: Clip) => void }) {
  return <div className="timeline-rows"><div className="day-mark">Today<span /></div>{clips.map((clip, index) => <div key={clip.id}>
    {index > 0 && <div className="quiet-stretch mono">{Math.max(1, Number(clips[index - 1].hour) - Number(clip.hour) - 1)} quiet hours</div>}
    {onOpen ? <button className="timeline-moment" onClick={() => onOpen(clip)}><span className="hour">{clip.hour}</span><span className="moment-body"><Waveform count={56} seed={clip.seed} /><span className="clip-meta"><span>{clip.title}</span><span>{formatTime(clip.duration)}</span><small>TEXT</small></span></span></button> : <div className="timeline-moment"><span className="hour">{clip.hour}</span><span className="moment-body"><Waveform count={56} seed={clip.seed} /><span className="clip-meta"><span>{clip.title}</span><span>{formatTime(clip.duration)}</span><small>TEXT</small></span></span></div>}
  </div>)}</div>;
}
export function PixelPhone() {
  return <div className="pixel-phone"><div className="phone-camera" /><div className="phone-status mono">17:03 <span className="phone-status-icons"><Icon name="signal" /><Icon name="battery" /></span></div><div className="phone-topbar">Hindsight <Icon name="search" /></div><TimelineRows /><div className="phone-home" /></div>;
}
export default function PhoneTimeline() {
  const [clips, setClips] = useState(SAMPLES);
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [position, setPosition] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [deleted, setDeleted] = useState<Clip | null>(null);
  const [notice, setNotice] = useState('');
  const [renaming, setRenaming] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [draft, setDraft] = useState('');
  const root = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const selected = clips.find(c => c.id === selectedId);
  useEffect(() => {
    if (!playing || !selected) return;
    const timer = setInterval(() => {
      if (!document.hidden) setPosition(p => Math.min(selected.duration, p + 0.1 * speed));
    }, 100);
    return () => clearInterval(timer);
  }, [playing, speed, selected]);
  useEffect(() => { if (selected && position >= selected.duration) setPlaying(false); }, [position, selected]);
  useEffect(() => {
    const observer = new IntersectionObserver(([entry]) => { if (!entry.isIntersecting) setPlaying(false); });
    if (root.current) observer.observe(root.current);
    return () => observer.disconnect();
  }, []);
  function open(clip: Clip) { setSelectedId(clip.id); setPosition(0); setPlaying(false); setRenaming(false); setConfirming(false); }
  useEffect(() => { if (selectedId !== null) heading.current?.focus(); }, [selectedId]);
  const filtered = clips.filter(c => `${c.title} ${c.transcript}`.toLowerCase().includes(query.toLowerCase()));
  return <div className="phone-archive" ref={root}>
    <div className="archive-label"><span className="eyebrow">The phone archive</span><span className="mono">SAMPLE DATA · SILENT DEMO</span></div>
    {!selected ? <>
      <div className="archive-header"><h3>Hindsight</h3><label className="search-field"><Icon name="search" /><input ref={search} aria-label="Search names and speech" placeholder="Search names and speech" value={query} onChange={e => setQuery(e.target.value)} />{query && <button aria-label="Clear search" onClick={() => { setQuery(''); search.current?.focus(); }}><Icon name="close" /></button>}</label></div>
      <div className="search-suggestions"><span>Try a remembered word:</span>{['restaurant', 'lemon'].map(word => <button key={word} onClick={() => setQuery(word)}>{word} <Icon name="arrow-up-right" /></button>)}</div>
      <p className="sr-only" role="status">{filtered.length} matching clips</p>
      {filtered.length ? <TimelineRows clips={filtered} onOpen={open} /> : <div className="empty-state"><h4>No matches</h4><p>Nothing here mentions “{query}”.</p></div>}
      <p className="archive-hint mono">Choose a moment. Come back to it.</p>
    </> : <div className="clip-player">
      <div className="player-header"><button aria-label="Back to timeline" onClick={() => { setSelectedId(null); setPlaying(false); }}><Icon name="arrow-left" /></button><h3 ref={heading} tabIndex={-1}>{selected.title}</h3><button onClick={() => { setDraft(selected.title); setRenaming(r => !r); }}>Rename</button><button onClick={() => setConfirming(true)}>Delete</button></div>
      <p className="player-date mono">Today · {selected.time}</p>
      {renaming && <form className="rename-form" onSubmit={e => { e.preventDefault(); setClips(cs => cs.map(c => c.id === selected.id ? { ...c, title: draft.trim() || c.time } : c)); setRenaming(false); setNotice('Clip renamed.'); }}><label>Name this clip<input autoFocus value={draft} maxLength={80} onChange={e => setDraft(e.target.value)} /></label><button type="submit">Save</button><button type="button" onClick={() => setRenaming(false)}>Cancel</button></form>}
      {confirming && <div className="delete-confirm" role="group" aria-label="Confirm deletion"><p>Delete this clip? You can undo straight away.</p><button onClick={() => { setDeleted(selected); setClips(cs => cs.filter(c => c.id !== selected.id)); setSelectedId(null); setPlaying(false); setConfirming(false); setNotice('Clip moved to trash.'); }}>Delete clip</button><button onClick={() => setConfirming(false)}>Cancel</button></div>}
      <div className="scrub-wave"><Waveform count={96} seed={selected.seed} progress={position / selected.duration} /><input type="range" min={0} max={selected.duration} step={0.1} value={position} aria-label="Playback position" aria-valuetext={`${formatTime(position)} of ${formatTime(selected.duration)}`} onChange={e => setPosition(Number(e.target.value))} /></div>
      <div className="axis-labels mono"><span>{formatTime(position)}</span><span>{formatTime(selected.duration)}</span></div>
      <div className="playback-controls"><button aria-label="Back 10 seconds" onClick={() => setPosition(p => Math.max(0, p - 10))}><Icon name="rewind" /> 10</button><button className="play-button" aria-label={playing ? 'Pause sample playback' : 'Play silent sample'} onClick={() => { if (position >= selected.duration) setPosition(0); setPlaying(p => !p); }}><Icon name={playing ? 'pause' : 'play'} /></button><button aria-label="Forward 10 seconds" onClick={() => setPosition(p => Math.min(selected.duration, p + 10))}>10 <Icon name="forward" /></button></div>
      <div className="speed-controls" aria-label="Playback speed">{[0.75, 1, 1.5, 2].map(s => <button key={s} aria-pressed={speed === s} onClick={() => setSpeed(s)}>{s}×</button>)}</div>
      <div className="transcript"><span className="eyebrow">Transcript</span><p>{selected.transcript}</p><small>Made-up conversation for this demo. In the app, transcripts help you find a moment, but can miss words.</small></div>
    </div>}
    <details className="technical-details transcription-details"><summary>A note about searching speech <Icon name="arrow-down" /></summary><p>Transcription happens on your phone, not a remote server. It needs Android 13 or newer, a supported on-device recognizer, and an installed speech model. Some passages may be missing. Use it to find a moment, not as an exact record of what was said.</p></details>
    <div className="archive-notice" role="status">{notice}{deleted && <button onClick={() => { setClips(cs => [...cs, deleted].sort((a, b) => b.time.localeCompare(a.time))); setDeleted(null); setNotice('Clip restored.'); }}>Undo</button>}</div>
  </div>;
}
