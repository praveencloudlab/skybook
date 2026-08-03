import { describe, expect, it } from 'vitest';
import { addDaysIso, dayOffset, duration, money, time } from './format';

describe('format', () => {
  it('shows the airport-local time, never the viewer’s local time', () => {
    // The single most important decision in this file. A 07:30 departure means
    // 07:30 at the airport you are standing in. Converting to the viewer's zone
    // would show a passenger in New York "02:30" for a London departure -
    // technically defensible, and completely wrong for someone catching a plane.
    expect(time('2026-08-01T07:30:00')).toBe('07:30');
    expect(time('2026-08-01T23:05:00')).toBe('23:05');
  });

  it('computes duration between two local timestamps', () => {
    expect(duration('2026-08-01T07:30:00', '2026-08-01T08:50:00')).toBe('1h 20m');
    expect(duration('2026-08-01T09:00:00', '2026-08-01T11:00:00')).toBe('2h');
  });

  it('handles an overnight flight without going negative', () => {
    // Long-haul routinely lands the next day; a naive same-day subtraction
    // yields a negative duration and renders as nonsense.
    expect(duration('2026-08-01T21:00:00', '2026-08-02T06:30:00')).toBe('9h 30m');
  });

  it('reports the arrival day offset', () => {
    // Surfaced as "+1" on the card - easy to miss, expensive to get wrong.
    expect(dayOffset('2026-08-01T21:00:00', '2026-08-02T06:30:00')).toBe(1);
    expect(dayOffset('2026-08-01T07:30:00', '2026-08-01T08:50:00')).toBe(0);
  });

  it('formats money, and renders absent amounts as a dash', () => {
    expect(money(120)).toBe('£120.00');
    expect(money('85.50')).toBe('£85.50');
    // A missing fare must not render as "£NaN" or "£0.00" - one looks broken,
    // the other looks free.
    expect(money(null)).toBe('—');
    expect(money(undefined)).toBe('—');
  });

  it('uses the currency the server sent, not a hardcoded one', () => {
    // Caught live: /api/bookings/quote returns USD for the seeded fares. A
    // hardcoded GBP formatter rendered $85.00 as "£85.00" - a booking screen
    // stating a false price, which is the one thing it must never do.
    expect(money(85, 'USD')).toBe('US$85.00');
    expect(money(85, 'EUR')).toBe('€85.00');
    expect(money(85, 'GBP')).toBe('£85.00');
  });

  it('still shows the number when the currency code is unrecognised', () => {
    // Blanking the price entirely would be worse than showing it unstyled.
    expect(money(85, 'NOTACURRENCY')).toBe('85.00 NOTACURRENCY');
  });

  it('adds days across a month boundary', () => {
    expect(addDaysIso('2026-08-31', 1)).toBe('2026-09-01');
    expect(addDaysIso('2026-12-31', 1)).toBe('2027-01-01');
  });
});
