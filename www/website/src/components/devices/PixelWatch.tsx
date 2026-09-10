import Waveform from '../hindsight/Waveform';
import { formatTime, watchFeedback, type SavePhase } from '../../lib/product';

export function WatchScreen({ phase = 'listening', retention = 1, buffered = 60, onSave, live = true, peaks, savedDuration = buffered }: { phase?: SavePhase; retention?: number; buffered?: number; onSave?: () => void; live?: boolean; peaks?: number[]; savedDuration?: number }) {
  const content = <>
    <span className="watch-clock">17:02</span>
    <span className="watch-status">Listening</span>
    <span className="watch-buffer">{formatTime(buffered)} / {formatTime(retention * 60)}</span>
    <Waveform watch live={live} peaks={peaks} />
    <span className="watch-action">{phase === 'saving' ? 'Saving…' : 'Tap to save'}</span>
    <span className="watch-feedback" data-watch-feedback>{watchFeedback(phase, savedDuration)}</span>
    <span className="watch-pager"><i /><i /></span>
    <span className="tap-ripple" />
  </>;
  return onSave ? <button className="watch-screen" onClick={onSave} disabled={phase === 'saving'} aria-label="Save buffered sample audio">{content}</button> : <div className="watch-screen" role="img" aria-label={`Hindsight watch: listening, ${retention} minute buffer`}>{content}</div>;
}
export default function PixelWatch(props: Parameters<typeof WatchScreen>[0]) {
  return <div className="pixel-watch"><div className="watch-strap strap-top" /><div className="watch-strap strap-bottom" /><div className="watch-crown" /><div className="watch-case"><WatchScreen {...props} /></div></div>;
}
