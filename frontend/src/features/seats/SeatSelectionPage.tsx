import { useEffect, useState } from 'react';
import type { Flight } from '../../api/flights';
import type { PassengerType } from '../../api/bookings';
import { FARE_TYPE_LABELS, TRAVEL_CLASS_LABELS, type FareType, type TravelClass } from '../../api/quotes';
import { seatsApi, type AircraftSeat, type FlightSeatMap } from '../../api/seats';
import type { Travellers } from '../../components/TravellersPicker';
import { BookingStepper } from '../../components/BookingStepper';
import { SummaryRail } from '../../components/SummaryRail';
import { ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { ApiError } from '../../lib/errors';
import { money } from '../../lib/format';
import type { PassengerDraft } from '../booking/PassengerForm';
import { SeatMap } from './SeatMap';

/**
 * Seat selection (carrier flow step 3): a tab per guest across the top - each
 * showing its chosen seat and price - the cabin below, and every selected seat
 * stamped with its guest's initials. Entirely optional: "Add seats later"
 * continues with free auto-assignment at check-in.
 *
 * <p>Only the cabin the fare bought is shown - offering the whole aircraft
 * would let someone pick a Business seat on an Economy fare and be refused
 * later. Lap infants get no seat, so they get no tab.
 */
export function SeatSelectionPage({
  flight,
  legLabel,
  cabin,
  fare,
  baseFare,
  currency,
  travellers,
  paxTypes,
  guests,
  onBack,
  onContinue,
}: {
  flight: Flight;
  /** Round trip: which leg this seat map is for ("Outbound" / "Return"). */
  legLabel?: string;
  cabin: TravelClass;
  fare: FareType;
  baseFare: number;
  currency: string;
  travellers: Travellers;
  paxTypes: PassengerType[];
  guests: PassengerDraft[];
  onBack: () => void;
  onContinue?: (seats: AircraftSeat[]) => void;
}) {
  const [map, setMap] = useState<FlightSeatMap | null>(null);
  const [selected, setSelected] = useState<AircraftSeat[]>([]);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(true);

  // Lap infants don't hold a seat - the selectable party is everyone else.
  const seatedCount = paxTypes.filter((t) => t !== 'INFANT').length;

  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setError(null);

    seatsApi
      .forFlight(flight.id, controller.signal)
      .then(setMap)
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') {
          return;
        }
        setError(cause instanceof ApiError ? cause : null);
      })
      .finally(() => setBusy(false));

    return () => controller.abort();
  }, [flight.id]);

  /**
   * Toggle a seat: picking again removes it; a fresh pick fills the next empty
   * slot, and once the party is fully seated a new pick swaps the LAST choice
   * (so changing your mind never requires a deselect-first dance).
   */
  function toggle(seat: AircraftSeat) {
    setSelected((current) => {
      const at = current.findIndex((s) => s.seatNumber === seat.seatNumber);
      if (at >= 0) {
        return current.filter((_, i) => i !== at);
      }
      if (current.length < seatedCount) {
        return [...current, seat];
      }
      return [...current.slice(0, -1), seat];
    });
  }

  const seatedGuests = guests.filter((_, i) => paxTypes[i] !== 'INFANT');
  const nameOf = (index: number) =>
    `${seatedGuests[index]?.firstName ?? ''} ${seatedGuests[index]?.lastName ?? ''}`.trim() ||
    `Guest ${index + 1}`;
  const initialsOf = (index: number) => {
    const g = seatedGuests[index];
    const init = `${g?.firstName?.[0] ?? ''}${g?.lastName?.[0] ?? ''}`.toUpperCase();
    return init || `P${index + 1}`;
  };

  // Flexi and Premium include free seat selection - the server waives the
  // charge, so the page must never show one.
  const freeSeats = fare !== 'SAVER';
  const chargeOf = (seat: AircraftSeat) => (freeSeats ? 0 : Number(seat.listedSurcharge) || 0);
  const surchargeTotal = selected.reduce((sum, s) => sum + chargeOf(s), 0);
  const total = baseFare * guests.length + surchargeTotal;

  return (
    <>
      <BookingStepper
        current="seats"
        flight={flight}
        route={`${flight.originAirportCode} → ${flight.destinationAirportCode}`}
        onModify={onBack}
      />

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[1fr_320px]">
        <div className="rounded-2xl bg-white p-5 shadow-[var(--shadow-card)] sm:p-7">
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Seat selection
            {legLabel ? (
              <span className="ml-2 align-middle rounded-full bg-brand-50 px-2.5 py-1 text-sm font-semibold text-brand-700 ring-1 ring-inset ring-brand-100">
                {legLabel} · {flight.originAirportCode} → {flight.destinationAirportCode}
              </span>
            ) : null}
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            {TRAVEL_CLASS_LABELS[cabin]} · {FARE_TYPE_LABELS[fare]} — secure your seat now, or let us
            assign one free at check-in.
          </p>

          {/* Guest tabs: whose seat is being picked, and what it costs. */}
          <div className="mt-4 flex flex-wrap gap-1 border-b border-slate-200">
            {seatedGuests.map((_, index) => {
              const seat = selected[index];
              const active = index === Math.min(selected.length, seatedCount - 1);
              return (
                <div
                  key={index}
                  className={
                    'px-4 py-2 text-center text-sm ' +
                    (active ? 'border-b-2 border-brand-950 font-bold text-slate-900' : 'text-slate-400')
                  }
                >
                  <div>{nameOf(index)}</div>
                  <div className="tabular text-xs">
                    {seat
                      ? `${seat.seatNumber} · ${chargeOf(seat) > 0 ? money(chargeOf(seat), currency) : 'Free'}`
                      : '—'}
                  </div>
                </div>
              );
            })}
          </div>

          <div className="mt-5 space-y-4">
            <ErrorAlert error={error} />

            {busy ? (
              <p className="rounded-xl bg-slate-50 px-3 py-2 text-sm text-slate-500">Loading the cabin…</p>
            ) : map ? (
              <SeatMap
                map={map}
                cabin={cabin}
                currency={currency}
                freeSeats={freeSeats}
                paxCount={seatedCount}
                selected={selected}
                initials={seatedGuests.map((_, i) => initialsOf(i))}
                onToggle={toggle}
                onClear={() => setSelected([])}
              />
            ) : null}
          </div>

          <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
            <button
              type="button"
              onClick={onBack}
              className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              Back
            </button>
            <div className="flex items-center gap-3">
              {selected.length === 0 ? (
                <button
                  type="button"
                  onClick={() => onContinue?.([])}
                  className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
                >
                  Add seats later
                </button>
              ) : null}
              <Button onClick={() => onContinue?.(selected)} disabled={!onContinue}>
                Continue
              </Button>
            </div>
          </div>
        </div>

        <SummaryRail
          flight={flight}
          cabin={cabin}
          fare={fare}
          currency={currency}
          travellers={travellers}
          guestNames={guests.map((g) => `${g.title} ${g.firstName} ${g.lastName}`.trim())}
          extras={selected.map((seat, i) => ({
            label: `${nameOf(i)} · Seat ${seat.seatNumber}`,
            amount: chargeOf(seat),
          }))}
          total={total}
        />
      </main>
    </>
  );
}
