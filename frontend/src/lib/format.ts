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

const MONEY = new Intl.NumberFormat('en-GB', {
  style: 'currency',
  currency: 'GBP',
  minimumFractionDigits: 2,
});

export function money(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === '') {
    return '—';
  }
  const value = typeof amount === 'string' ? Number(amount) : amount;
  return Number.isFinite(value) ? MONEY.format(value) : '—';
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
