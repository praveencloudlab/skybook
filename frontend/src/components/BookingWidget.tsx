import { useState, type FormEvent } from 'react';
import type { TravelClass } from '../api/quotes';
import { AirportField } from './AirportField';
import { FareCalendar } from './FareCalendar';
import { GuestsCabinPicker } from './GuestsCabinPicker';
import { ONE_ADULT, type Travellers } from './TravellersPicker';

/** Everything the widget collects - one submission's worth of search intent. */
export interface BookingSearch {
  origin: string;
  destination: string;
  date: string;
  travellers: Travellers;
  cabin: TravelClass;
}

/**
 * The booking widget - the premium-carrier search panel used on the landing
 * hero and the search page: trip-type tabs docked to a white panel (One way
 * active; the other types shown the way the airline shows them but honestly
 * disabled), outlined field tiles with gold icon discs, the full-width
 * "Guests and Cabin" panel, the three-month fare calendar, and the gold
 * Search pill.
 */
export function BookingWidget({
  initial,
  busy = false,
  onSearch,
}: {
  initial?: Partial<BookingSearch>;
  busy?: boolean;
  onSearch: (search: BookingSearch) => void;
}) {
  const [origin, setOrigin] = useState(initial?.origin ?? 'LHR');
  const [destination, setDestination] = useState(initial?.destination ?? 'DXB');
  const [date, setDate] = useState(initial?.date ?? defaultDate());
  const [travellers, setTravellers] = useState<Travellers>(initial?.travellers ?? ONE_ADULT);
  const [cabin, setCabin] = useState<TravelClass>(initial?.cabin ?? 'ECONOMY');

  const sameAirport = origin === destination;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sameAirport) {
      return;
    }
    onSearch({ origin, destination, date, travellers, cabin });
  }

  function swap() {
    setOrigin(destination);
    setDestination(origin);
  }

  return (
    <form onSubmit={submit} className="relative z-20">
      {/* Trip-type tabs: only one-way flying exists today. */}
      <div className="inline-flex items-center gap-1 rounded-t-2xl bg-white px-2 pt-2">
        <span
          aria-disabled="true"
          title="Not available yet"
          className="cursor-not-allowed rounded-full px-4 py-1.5 text-sm font-semibold text-slate-400"
        >
          Round trip
        </span>
        <span className="rounded-full bg-brand-900 px-4 py-1.5 text-sm font-semibold text-white">
          One way
        </span>
        <span
          aria-disabled="true"
          title="Not available yet"
          className="cursor-not-allowed rounded-full px-4 py-1.5 text-sm font-semibold text-slate-400"
        >
          Multi-city
        </span>
      </div>

      {/* The relative container both full-width panels resolve against. */}
      <div className="relative rounded-b-2xl rounded-tr-2xl bg-white p-4 shadow-[var(--shadow-float)]">
        <div className="grid items-center gap-2 md:grid-cols-[1fr_auto_1fr_1fr_1fr_auto]">
          <AirportField label="From" value={origin} onChange={setOrigin} exclude={destination} />

          <button
            type="button"
            onClick={swap}
            aria-label="Swap origin and destination"
            className="hidden h-8 w-8 shrink-0 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 md:grid"
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
              <path d="M7 7h11l-3-3 1.4-1.4L21.8 8 16.4 13.4 15 12l3-3H7V7zm10 10H6l3 3-1.4 1.4L2.2 16 7.6 10.6 9 12l-3 3h11v2z" />
            </svg>
          </button>

          <AirportField label="To" value={destination} onChange={setDestination} exclude={origin} />

          <GuestsCabinPicker
            travellers={travellers}
            cabin={cabin}
            onTravellers={setTravellers}
            onCabin={setCabin}
          />

          <FareCalendar
            origin={origin}
            destination={destination}
            cabin={cabin}
            value={date}
            onChange={setDate}
          />

          <button
            type="submit"
            disabled={sameAirport || busy}
            className="inline-flex h-[52px] items-center justify-center gap-2 rounded-full bg-accent-500 px-8 text-base font-bold text-white transition-colors hover:bg-accent-600 focus-visible:ring-2 focus-visible:ring-accent-500/60 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-accent-200"
          >
            {busy ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/60 border-t-transparent" />
            ) : null}
            Search
          </button>
        </div>

        {sameAirport ? (
          <p className="mt-2 text-sm font-medium text-red-600">
            Origin and destination must be different.
          </p>
        ) : null}
      </div>
    </form>
  );
}

function defaultDate(): string {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  return `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`;
}
