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

  if (failed || code === 'SB') {
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
