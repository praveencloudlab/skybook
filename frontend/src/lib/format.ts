/**
 * Display formatting.
 *
 * <p>Centralised because times and money are the two things a passenger reads
 * most carefully on an airline site, and inconsistency in either erodes trust
 * faster than almost anything else on the page.
 */

/**
 * Times are rendered as the server sent them - NOT converted to the viewer's
 * local zone.
 *
 * <p>A departure at 07:30 means 07:30 at the airport you are standing in. Doing
 * a timezone conversion here would show a passenger in New York "02:30" for a
 * London departure, which is technically defensible and completely wrong for
 * someone catching a plane. The API sends local-to-the-airport times, so we
 * print them verbatim.
 */
export function time(isoLocal: string): string {
  const timePart = isoLocal.split('T')[1] ?? '';
  return timePart.slice(0, 5);
}

export function dayAndMonth(isoLocal: string): string {
  const [datePart] = isoLocal.split('T');
  const [year, month, day] = datePart.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  });
}

/** Whole days between two local dates - i.e. "arrives +1". */
export function dayOffset(fromIso: string, toIso: string): number {
  const from = Date.parse(`${fromIso.split('T')[0]}T00:00:00Z`);
  const to = Date.parse(`${toIso.split('T')[0]}T00:00:00Z`);
  return Math.round((to - from) / 86_400_000);
}

/**
 * Elapsed time between two local timestamps.
 *
 * <p>Both are parsed as UTC (the trailing Z) purely so the subtraction is not
 * shifted by the viewer's own offset - we want the difference between the two
 * clock readings, which is the flight's duration, not a conversion of either.
 */
export function duration(fromIso: string, toIso: string): string {
  const minutes = Math.round((Date.parse(`${toIso}Z`) - Date.parse(`${fromIso}Z`)) / 60_000);
  if (!Number.isFinite(minutes) || minutes <= 0) {
    return '';
  }
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
}

/**
 * Money, in the currency the SERVER said.
 *
 * <p>The currency is a parameter, not a constant, because it is not ours to
 * assume: the quote endpoint returns USD for the seeded fares, and hardcoding
 * GBP would render $85.00 as "£85.00" - a booking screen stating a false price.
 * Defaults to GBP only for the rare call site with genuinely no currency to
 * hand.
 *
 * <p>Formatters are cached: constructing an Intl.NumberFormat is comparatively
 * expensive, and a fare table builds one per cell otherwise.
 */
const MONEY_FORMATTERS = new Map<string, Intl.NumberFormat>();

function formatterFor(currency: string): Intl.NumberFormat {
  let formatter = MONEY_FORMATTERS.get(currency);
  if (!formatter) {
    formatter = new Intl.NumberFormat('en-GB', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
    });
    MONEY_FORMATTERS.set(currency, formatter);
  }
  return formatter;
}

export function money(
  amount: number | string | null | undefined,
  currency = 'GBP',
): string {
  if (amount === null || amount === undefined || amount === '') {
    return '—';
  }
  const value = typeof amount === 'string' ? Number(amount) : amount;
  if (!Number.isFinite(value)) {
    return '—';
  }
  try {
    return formatterFor(currency).format(value);
  } catch {
    // An unrecognised currency code must not blank out the price entirely -
    // showing "85.00 XYZ" is far better than showing nothing.
    return `${value.toFixed(2)} ${currency}`;
  }
}

/** Today in the yyyy-MM-dd shape the API expects, in the viewer's own date. */
export function todayIso(): string {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

export function addDaysIso(iso: string, days: number): string {
  const [year, month, day] = iso.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day + days));
  return date.toISOString().slice(0, 10);
}
