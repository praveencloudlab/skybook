/**
 * Display formatting.
 *
 * <p>Centralised because times and money are the two things a passenger reads
 * most carefully on an airline site, and inconsistency in either erodes trust
 * faster than almost anything else on the page.
 */

import { notifyLocaleChanged } from './i18n';

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

/**
 * A clock reading shifted by N minutes, e.g. boarding 10:15 - 30 => "09:45".
 *
 * <p>Same no-timezone-conversion rule as {@link time}: the input is a
 * local-to-the-airport timestamp, so it is parsed and re-emitted as UTC purely
 * to do the arithmetic without the viewer's own offset leaking in.
 */
export function timeShift(isoLocal: string, deltaMinutes: number): string {
  const date = new Date(`${isoLocal.slice(0, 16)}:00Z`);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  date.setUTCMinutes(date.getUTCMinutes() + deltaMinutes);
  return `${String(date.getUTCHours()).padStart(2, '0')}:${String(date.getUTCMinutes()).padStart(2, '0')}`;
}

/** A full readable date, e.g. "29 Jul 2026" - for documents like the boarding pass. */
export function dayMonthYear(isoLocal: string): string {
  const [datePart] = isoLocal.split('T');
  const [year, month, day] = datePart.split('-').map(Number);
  if (!year || !month || !day) {
    return '—';
  }
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
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
 * A count of minutes as "13h 5m".
 *
 * <p>Minutes, not two timestamps, because a flight's two timestamps are wall
 * clocks at DIFFERENT airports and subtracting them measures the flight plus
 * the offset between the zones. London 21:25 to Singapore 17:30 subtracts to
 * 20h 05m and is 13h 05m in the air. Only the server knows which zone each
 * airport is in, so the server sends the answer and this only formats it.
 */
export function durationFromMinutes(minutes: number | null | undefined): string {
  if (minutes == null || !Number.isFinite(minutes) || minutes <= 0) {
    return '';
  }
  const whole = Math.round(minutes);
  const hours = Math.floor(whole / 60);
  const mins = whole % 60;
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
}

/**
 * Elapsed time between two timestamps that are on the SAME clock.
 *
 * <p>Both are parsed as UTC (the trailing Z) purely so the subtraction is not
 * shifted by the viewer's own offset.
 *
 * <p>Only valid where one clock governs both readings - a layover, where the
 * passenger sits in one airport. For a flight use {@link durationFromMinutes}
 * with the server's figure.
 */
export function sameClockDuration(fromIso: string, toIso: string): string {
  const minutes = Math.round((Date.parse(`${toIso}Z`) - Date.parse(`${fromIso}Z`)) / 60_000);
  return durationFromMinutes(minutes);
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

/**
 * Display-currency support (shopping surfaces only). Reference rates FROM
 * GBP - fixed, clearly approximate; the platform always CHARGES in the
 * booking's own currency, so receipts and paid amounts must keep using
 * money() with the stored currency, never price().
 */
export const DISPLAY_CURRENCIES = [
  { code: 'GBP', label: '£ GBP' },
  { code: 'USD', label: '$ USD' },
  { code: 'EUR', label: '€ EUR' },
  { code: 'INR', label: '₹ INR' },
  { code: 'AED', label: 'د.إ AED' },
  { code: 'JPY', label: '¥ JPY' },
] as const;

const RATES_FROM_GBP: Record<string, number> = {
  GBP: 1,
  USD: 1.27,
  EUR: 1.17,
  INR: 106,
  AED: 4.67,
  JPY: 190,
};

const CURRENCY_KEY = 'skybook.currency';

export function displayCurrency(): string {
  try {
    const raw = localStorage.getItem(CURRENCY_KEY);
    return raw && RATES_FROM_GBP[raw] ? raw : 'GBP';
  } catch {
    return 'GBP';
  }
}

export function setDisplayCurrency(code: string): void {
  try {
    localStorage.setItem(CURRENCY_KEY, code);
  } catch {
    // Private mode etc - the switch simply won't stick beyond this visit.
  }
  // price() reads the stored choice on every call, so an in-place re-render
  // from the locale store is all a currency switch needs - no reload.
  notifyLocaleChanged();
}

/**
 * A SHOPPING price in the viewer's chosen display currency, converted from
 * the source currency at the fixed reference rate. Falls back to exact
 * money() when the source rate is unknown or no conversion is needed.
 */
export function price(
  amount: number | string | null | undefined,
  sourceCurrency = 'GBP',
): string {
  const display = displayCurrency();
  const value = typeof amount === 'string' ? Number(amount) : amount;
  if (value === null || value === undefined || !Number.isFinite(value)
      || display === sourceCurrency || !RATES_FROM_GBP[sourceCurrency]) {
    return money(amount, sourceCurrency);
  }
  const converted = (value / RATES_FROM_GBP[sourceCurrency]) * RATES_FROM_GBP[display];
  return money(display === 'JPY' ? Math.round(converted) : Math.round(converted * 100) / 100, display);
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
