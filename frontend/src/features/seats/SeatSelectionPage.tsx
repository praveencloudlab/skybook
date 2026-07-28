import { useEffect, useState } from 'react';
import type { Flight } from '../../api/flights';
import { FARE_TYPE_LABELS, TRAVEL_CLASS_LABELS, type FareType, type TravelClass } from '../../api/quotes';
import { seatsApi, type AircraftSeat, type FlightSeatMap } from '../../api/seats';
import { ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { TripSummaryBar } from '../../components/TripSummaryBar';
import { ApiError } from '../../lib/errors';
import { money } from '../../lib/format';
import { SeatMap } from './SeatMap';

/**
 * Choose seats (FRONTEND_MODULE.md §5 screen 4).
 *
 * <p>Only the cabin the passenger bought is shown, because a fare buys a cabin -
 * offering the whole aircraft would let someone pick a Business seat on an
 * Economy fare and be refused later.
 *
 * <p>One seat per traveller: the count chosen at search sizes the selection,
 * and every passenger - not just the first - can have a picked seat. Choosing
 * fewer than the party size is fine; the rest are assigned free at check-in.
 */
export function SeatSelectionPage({
  flight,
  cabin,
  fare,
  baseFare,
  currency,
  paxCount,
  onBack,
  onContinue,
}: {
  flight: Flight;
  cabin: TravelClass;
  fare: FareType;
  baseFare: number;
  currency: string;
  paxCount: number;
  onBack: () => void;
  onContinue?: (seats: AircraftSeat[]) => void;
}) {
  const [map, setMap] = useState<FlightSeatMap | null>(null);
  const [selected, setSelected] = useState<AircraftSeat[]>([]);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(true);

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
      if (current.length < paxCount) {
        return [...current, seat];
      }
      return [...current.slice(0, -1), seat];
    });
  }

  const surchargeTotal = selected.reduce((sum, s) => sum + (Number(s.listedSurcharge) || 0), 0);
  const total = baseFare * paxCount + surchargeTotal;

  return (
    <>
      <TripSummaryBar flight={flight} step="seat" onBack={onBack} backLabel="Back to fares" />

      <main className="mx-auto max-w-3xl px-6 py-8">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
          {paxCount === 1 ? 'Choose your seat' : 'Choose your seats'}
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          {TRAVEL_CLASS_LABELS[cabin]} · {FARE_TYPE_LABELS[fare]}
          {paxCount > 1 ? ` · ${paxCount} travellers` : ''}
        </p>

        <div className="mt-6 space-y-4">
        <ErrorAlert error={error} />

        {busy ? (
          <p className="card px-3 py-2 text-sm text-slate-500">
            Loading the cabin…
          </p>
        ) : map ? (
          <>
            <SeatMap
              map={map}
              cabin={cabin}
              currency={currency}
              paxCount={paxCount}
              selected={selected}
              onToggle={toggle}
              onClear={() => setSelected([])}
            />

            {/* The running total, so a surcharge is never a surprise later. */}
            <dl className="card text-sm">
              <div className="flex justify-between px-4 py-2">
                <dt className="text-slate-600">
                  Base fare{paxCount > 1 ? ` · ${money(baseFare, currency)} × ${paxCount}` : ''}
                </dt>
                <dd className="tabular text-slate-900">{money(baseFare * paxCount, currency)}</dd>
              </div>
              {Array.from({ length: paxCount }, (_, i) => {
                const seat = selected[i];
                const charge = seat ? Number(seat.listedSurcharge) || 0 : 0;
                return (
                  <div key={i} className="flex justify-between border-t border-slate-100 px-4 py-2">
                    <dt className="text-slate-600">
                      Passenger {i + 1} · seat {seat ? seat.seatNumber : '(assigned for you)'}
                    </dt>
                    <dd className="tabular text-slate-900">
                      {charge > 0 ? money(charge, currency) : 'Free'}
                    </dd>
                  </div>
                );
              })}
              <div className="flex justify-between border-t border-slate-200 px-4 py-2 font-medium">
                <dt className="text-slate-900">Total</dt>
                <dd className="tabular text-slate-900">{money(total, currency)}</dd>
              </div>
            </dl>

            <div className="flex justify-end">
              <Button onClick={() => onContinue?.(selected)} disabled={!onContinue}>
                Continue
              </Button>
            </div>
          </>
        ) : null}
        </div>
      </main>
    </>
  );
}
