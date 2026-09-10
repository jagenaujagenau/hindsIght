const paths = {
  'arrow-up-right': 'M7 17 17 7M7 7h10v10',
  'arrow-down': 'M12 4v16m-6-6 6 6 6-6',
  'arrow-up': 'M12 20V4m-6 6 6-6 6 6',
  'arrow-right': 'M4 12h16m-6-6 6 6-6 6',
  'arrow-left': 'M20 12H4m6-6-6 6 6 6',
  search: 'm16 16 5 5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  close: 'm6 6 12 12M6 18 18 6',
  play: 'm8 5 11 7-11 7Z',
  pause: 'M8 5v14M16 5v14',
  rewind: 'M4 10h7M4 10V3m0 7a9 9 0 1 1 0 6',
  forward: 'M20 10h-7m7 0V3m0 7a9 9 0 1 0 0 6',
  signal: 'M4 19v-3m5 3v-7m5 7V8m5 11V4',
  battery: 'M3 7h16v10H3ZM22 10v4',
};
export type IconName = keyof typeof paths;
/** Decorative icons: the containing link/button supplies its accessible name. */
export default function Icon({ name, className = '' }: { name: IconName; className?: string }) {
  return <svg className={`icon ${className}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false"><path d={paths[name]} /></svg>;
}
