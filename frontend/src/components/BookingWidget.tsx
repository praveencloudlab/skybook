import { t } from '../lib/i18n';
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
  /** Present = round trip: the inbound (destination -> origin) date. */
  returnDate?: string;
  /**
   * Present = multi-city: ALL legs in travel order (the first mirrors
   * origin/destination/date). Legs are picked one at a time on the results
   * page and book as segments of ONE PNR.
   */
  legs?: { origin: string; destination: string; date: string }[];
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
  const [tripType, setTripType] = useState<'oneway' | 'round' | 'multi'>(initial?.returnDate ? 'round' : 'oneway');
  const [returnDate, setReturnDate] = useState(initial?.returnDate ?? '');
  // Multi-city onward legs (leg 1 is the From/To/date tiles above); each
  // starts where the previous ended.
  const [extraLegs, setExtraLegs] = useState<{ destination: string; date: string }[]>([]);
  const [travellers, setTravellers] = useState<Travellers>(initial?.travellers ?? ONE_ADULT);
  const [cabin, setCabin] = useState<TravelClass>(initial?.cabin ?? 'ECONOMY');

  const sameAirport = origin === destination;

  /** Legs in travel order: leg n departs where leg n-1 landed. */
  function buildLegs(): { origin: string; destination: string; date: string }[] {
    const legs = [{ origin, destination, date }];
    let from = destination;
    for (const leg of extraLegs) {
      legs.push({ origin: from, destination: leg.destination, date: leg.date });
      from = leg.destination;
    }
    return legs;
  }

  const multiValid =
    tripType !== 'multi' ||
    (extraLegs.length > 0 &&
      buildLegs().every(
        (leg, i, all) =>
          leg.destination !== '' &&
          leg.origin !== leg.destination &&
          leg.date !== '' &&
          (i === 0 || leg.date >= all[i - 1].date),
      ));

  function submit(event: FormEvent) {
    event.preventDefault();
    if (sameAirport) {
      return;
    }
    if (tripType === 'round' && (!returnDate || returnDate < date)) {
      return;
    }
    if (tripType === 'multi' && !multiValid) {
      return;
    }
    onSearch({
      origin,
      destination,
      date,
      travellers,
      cabin,
      ...(tripType === 'round' ? { returnDate } : {}),
      ...(tripType === 'multi' ? { legs: buildLegs() } : {}),
    });
  }

  function swap() {
    setOrigin(destination);
    setDestination(origin);
  }

  return (
    <form onSubmit={submit} className="relative z-20">
      {/* Trip-type tabs: only one-way flying exists today. */}
      <div className="inline-flex items-center gap-1 rounded-t-2xl bg-white px-2 pt-2">
        <button
          type="button"
          onClick={() => {
            setTripType('round');
            setReturnDate((prev) => prev || plusDays(date, 7));
          }}
          aria-pressed={tripType === 'round'}
          className={
            'flex min-h-11 items-center rounded-full px-4 text-sm font-semibold transition sm:min-h-0 sm:py-1.5 ' +
            (tripType === 'round' ? 'bg-brand-900 text-white' : 'text-slate-500 hover:text-slate-800')
          }
        >
          {t('widget.roundtrip')}
        </button>
        <button
          type="button"
          onClick={() => setTripType('oneway')}
          aria-pressed={tripType === 'oneway'}
          className={
            'flex min-h-11 items-center rounded-full px-4 text-sm font-semibold transition sm:min-h-0 sm:py-1.5 ' +
            (tripType === 'oneway' ? 'bg-brand-900 text-white' : 'text-slate-500 hover:text-slate-800')
          }
        >
          {t('widget.oneway')}
        </button>
        <button
          type="button"
          onClick={() => {
            setTripType('multi');
            setExtraLegs((prev) => (prev.length ? prev : [{ destination: '', date: '' }]));
          }}
          aria-pressed={tripType === 'multi'}
          className={
            'flex min-h-11 items-center rounded-full px-4 text-sm font-semibold transition sm:min-h-0 sm:py-1.5 ' +
            (tripType === 'multi' ? 'bg-brand-900 text-white' : 'text-slate-500 hover:text-slate-800')
          }
        >
          {t('widget.multicity')}
        </button>
      </div>

      {/* The relative container both full-width panels resolve against. */}
      <div className="relative rounded-b-2xl rounded-tr-2xl bg-white p-4 shadow-[var(--shadow-float)]">
        <div
          className={
            // lg, not md: at exactly 768 px the multi-column row wanted
            // 720 px of a 688 px panel and clipped its search button. A
            // tablet gets the stacked layout, which fits.
            'grid items-center gap-2 ' +
            (tripType === 'round'
              ? 'lg:grid-cols-[1fr_auto_1fr_1fr_1fr_1fr_auto]'
              : 'lg:grid-cols-[1fr_auto_1fr_1fr_1fr_auto]')
          }
        >
          <AirportField label={t('widget.from')} value={origin} onChange={setOrigin} exclude={destination} />

          <button
            type="button"
            onClick={swap}
            aria-label="Swap origin and destination"
            /*
              Was hidden below md, so a phone simply never had it - and
              reversing the route is one of the most-used controls on any
              flight search. On the stacked mobile layout it becomes a
              44 px circle sitting between From and To (the shape every
              airline app uses); the desktop row keeps the original quiet
              borderless icon.
            */
            className="grid h-11 w-11 shrink-0 place-items-center justify-self-end rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:bg-slate-50 hover:text-slate-700 md:h-8 md:w-8 md:justify-self-auto md:border-0 md:bg-transparent md:text-slate-400 md:shadow-none md:hover:bg-slate-100"
          >
            <svg viewBox="0 0 24 24" className="h-4 w-4 fill-current" aria-hidden="true">
              <path d="M7 7h11l-3-3 1.4-1.4L21.8 8 16.4 13.4 15 12l3-3H7V7zm10 10H6l3 3-1.4 1.4L2.2 16 7.6 10.6 9 12l-3 3h11v2z" />
            </svg>
          </button>

          <AirportField label={t('widget.to')} value={destination} onChange={setDestination} exclude={origin} />

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
            onChange={(d) => {
              setDate(d);
              if (returnDate && returnDate < d) {
                setReturnDate(d);
              }
            }}
          />

          {tripType === 'round' ? (
            <FareCalendar
              label={t('widget.return')}
              origin={destination}
              destination={origin}
              cabin={cabin}
              value={returnDate || date}
              onChange={setReturnDate}
            />
          ) : null}

          <button
            type="submit"
            disabled={sameAirport || busy}
            className="inline-flex h-[52px] items-center justify-center gap-2 rounded-full bg-accent-500 px-8 text-base font-bold text-white transition-colors hover:bg-accent-600 focus-visible:ring-2 focus-visible:ring-accent-500/60 focus-visible:outline-none disabled:cursor-not-allowed disabled:bg-accent-200"
          >
            {busy ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/60 border-t-transparent" />
            ) : null}
            {t('widget.search')}
          </button>
        </div>

        {/* Multi-city onward legs: each departs where the previous landed. */}
        {tripType === 'multi' ? (
          <div className="mt-3 space-y-2 border-t border-slate-100 pt-3">
            {extraLegs.map((leg, i) => {
              const from = i === 0 ? destination : extraLegs[i - 1].destination;
              const minDate = i === 0 ? date : extraLegs[i - 1].date || date;
              return (
                // lg, not md: at 768 px the four-column row wanted 720 px of a
                // 688 px panel and clipped its remove button off the edge. A
                // tablet gets the stacked layout, which fits.
                <div key={i} className="grid items-center gap-2 lg:grid-cols-[7rem_1fr_1fr_auto]">
                  <span className="text-xs font-semibold text-slate-500">
                    Leg {i + 2} · from <span className="tabular text-slate-800">{from || '—'}</span>
                  </span>
                  <AirportField
                    label="To"
                    value={leg.destination}
                    onChange={(code) =>
                      setExtraLegs((prev) => prev.map((l, j) => (j === i ? { ...l, destination: code } : l)))
                    }
                    exclude={from}
                  />
                  <label className="flex h-[52px] items-center gap-2 rounded-xl border border-slate-200 px-3">
                    <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">Date</span>
                    <input
                      type="date"
                      value={leg.date}
                      min={minDate}
                      onChange={(e) =>
                        setExtraLegs((prev) => prev.map((l, j) => (j === i ? { ...l, date: e.target.value } : l)))
                      }
                      className="tabular w-full text-sm font-semibold text-slate-900 outline-none"
                    />
                  </label>
                  <button
                    type="button"
                    aria-label={`Remove leg ${i + 2}`}
                    disabled={extraLegs.length <= 1}
                    onClick={() => setExtraLegs((prev) => prev.filter((_, j) => j !== i))}
                    className="grid h-9 w-9 place-items-center rounded-full text-slate-400 transition hover:bg-slate-100 hover:text-red-600 disabled:invisible"
                  >
                    ×
                  </button>
                </div>
              );
            })}
            {extraLegs.length < 2 ? (
              <button
                type="button"
                onClick={() => setExtraLegs((prev) => [...prev, { destination: '', date: '' }])}
                className="text-sm font-semibold text-brand-700 hover:underline"
              >
                + Add another flight
              </button>
            ) : null}
          </div>
        ) : null}

        {tripType === 'round' && returnDate && returnDate < date ? (
          <p className="mt-2 text-sm font-medium text-red-600">
            The return date can't be before the outbound date.
          </p>
        ) : null}
        {tripType === 'multi' && !multiValid && extraLegs.some((l) => l.destination || l.date) ? (
          <p className="mt-2 text-sm font-medium text-red-600">
            Each leg needs a destination and a date on or after the previous leg's.
          </p>
        ) : null}
        {sameAirport ? (
          <p className="mt-2 text-sm font-medium text-red-600">
            Origin and destination must be different.
          </p>
        ) : null}
      </div>
    </form>
  );
}

function plusDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`);
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function defaultDate(): string {
  const tomorrow = new Date();
  tomorrow.setDate(tomorrow.getDate() + 1);
  return `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`;
}
