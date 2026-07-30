import { useState } from 'react';

/**
 * Real airline logos on the results, the way a metasearch shows them. Logos
 * come from Kiwi's public airline-image CDN keyed by IATA code; anything
 * that fails to load (offline demo, unknown code) falls back to the branded
 * initials disc, and SkyBook Air (SB) always uses its own house disc - it
 * has no real-world logo to show.
 */
export function AirlineLogo({ code, className = 'h-8 w-8' }: { code: string; className?: string }) {
  const [failed, setFailed] = useState(false);

  // SkyBook Air's own mark - the brand roundel from the site header, so the
  // house carrier looks as designed as the real ones beside it.
  if (code === 'SB') {
    return (
      <span
        className={`grid shrink-0 place-items-center rounded-lg bg-gradient-to-br from-accent-400 to-accent-600 ${className}`}
        title="SkyBook Air"
      >
        <svg viewBox="0 0 24 24" className="h-[62%] w-[62%] fill-white" aria-label="SkyBook Air logo">
          <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
        </svg>
      </span>
    );
  }

  if (failed) {
    return (
      <span
        className={`grid shrink-0 place-items-center rounded-lg bg-brand-600 text-[10px] font-bold text-white ${className}`}
      >
        {code}
      </span>
    );
  }
  return (
    <img
      src={`https://images.kiwi.com/airlines/64/${code}.png`}
      alt={`${code} logo`}
      loading="lazy"
      onError={() => setFailed(true)}
      className={`shrink-0 rounded-lg bg-white object-contain p-0.5 ring-1 ring-slate-200 ${className}`}
    />
  );
}
