import type { Flight } from '../../api/flights';

/**
 * Results filtering + sorting (FRONTEND_MODULE.md §10.2 - left-rail filters).
 *
 * <p>Deliberately scoped to what the seed data can actually distinguish: every
 * seeded flight is a single direct leg, so a "stops" filter would be theatre.
 * What varies, and is worth filtering on, is the airline and the departure time
 * of day - so those are what the rail offers.
 */

export type SortKey = 'earliest' | 'latest' | 'shortest';
export type BucketId = 'morning' | 'afternoon' | 'evening' | 'night';

export interface TimeBucket {
  id: BucketId;
  label: string;
  range: string;
}

export const TIME_BUCKETS: TimeBucket[] = [
  { id: 'morning', label: 'Morning', range: '05:00 – 11:59' },
  { id: 'afternoon', label: 'Afternoon', range: '12:00 – 16:59' },
  { id: 'evening', label: 'Evening', range: '17:00 – 20:59' },
  { id: 'night', label: 'Night', range: '21:00 – 04:59' },
];

/** Which part of the day a flight leaves in - by airport-local hour (§format). */
export function departureBucket(flight: Flight): BucketId {
  const hour = Number((flight.departureTime.split('T')[1] ?? '').slice(0, 2));
  if (hour >= 5 && hour < 12) {
    return 'morning';
  }
  if (hour >= 12 && hour < 17) {
    return 'afternoon';
  }
  if (hour >= 17 && hour < 21) {
    return 'evening';
  }
  return 'night';
}

/** Flight duration in minutes (both ends read as the same clock - see format.duration). */
export function flightMinutes(flight: Flight): number {
  return (Date.parse(`${flight.arrivalTime}Z`) - Date.parse(`${flight.departureTime}Z`)) / 60_000;
}

export interface FilterState {
  /** airlineCode -> included. Absent code defaults to included. */
  airlines: Record<string, boolean>;
  buckets: Record<BucketId, boolean>;
  sort: SortKey;
}

/** Fresh filters for a new result set: everything on, earliest first. */
export function initialFilters(results: Flight[]): FilterState {
  const airlines: Record<string, boolean> = {};
  for (const flight of results) {
    airlines[flight.airlineCode] = true;
  }
  return {
    airlines,
    buckets: { morning: true, afternoon: true, evening: true, night: true },
    sort: 'earliest',
  };
}

export function applyFilters(results: Flight[], state: FilterState): Flight[] {
  const out = results.filter((flight) => {
    const airlineOk = state.airlines[flight.airlineCode] ?? true;
    const bucketOk = state.buckets[departureBucket(flight)] ?? true;
    return airlineOk && bucketOk;
  });
  out.sort((a, b) => {
    if (state.sort === 'shortest') {
      return flightMinutes(a) - flightMinutes(b);
    }
    const cmp = a.departureTime.localeCompare(b.departureTime);
    return state.sort === 'latest' ? -cmp : cmp;
  });
  return out;
}

export interface AirlineCount {
  code: string;
  count: number;
}

export function airlineCounts(results: Flight[]): AirlineCount[] {
  const counts = new Map<string, number>();
  for (const flight of results) {
    counts.set(flight.airlineCode, (counts.get(flight.airlineCode) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([code, count]) => ({ code, count }))
    .sort((a, b) => a.code.localeCompare(b.code));
}

export function bucketCounts(results: Flight[]): Record<BucketId, number> {
  const counts: Record<BucketId, number> = { morning: 0, afternoon: 0, evening: 0, night: 0 };
  for (const flight of results) {
    counts[departureBucket(flight)] += 1;
  }
  return counts;
}
