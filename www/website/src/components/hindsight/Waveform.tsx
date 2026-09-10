import { edgeFalloff, perceptualLevel, samplePeak } from '../../lib/product';

export default function Waveform({ count = 31, seed = 0, watch = false, progress = 0, peaks, live = false }: { count?: number; seed?: number; watch?: boolean; progress?: number; peaks?: number[]; live?: boolean }) {
  const width = 600;
  const barWidth = watch ? width / (count * 2 - 1) : width / (count * 1.7);
  const step = watch ? barWidth * 2 : width / count;
  return <svg className={`waveform ${watch ? 'watch-wave' : 'clip-wave'}`} data-live-wave={live ? '' : undefined} viewBox="0 0 600 180" preserveAspectRatio="none" aria-hidden="true">
    {watch && <rect x="0" y={90 - barWidth / 2} width="600" height={barWidth} rx={barWidth / 2} />}
    {Array.from({ length: count }, (_, i) => {
      const from = peaks ? Math.floor(i * peaks.length / count) : 0;
      const to = peaks ? Math.max(from + 1, Math.floor((i + 1) * peaks.length / count)) : 0;
      const amplitude = peaks ? Math.max(0, ...peaks.slice(from, to)) : perceptualLevel(samplePeak(i + seed));
      const half = Math.max(barWidth / 2, amplitude * 90 * (watch ? edgeFalloff(i, count) : 1));
      return <rect key={i} data-bar={i} x={i * step + (watch ? 0 : (step - barWidth) / 2)} y={Number((90 - half).toFixed(3))} width={barWidth} height={Number((half * 2).toFixed(3))} rx={barWidth / 2} className={i < Math.round(progress * count) ? 'played' : undefined} />;
    })}
  </svg>;
}
