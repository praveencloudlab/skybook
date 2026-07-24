import { useEffect, useState } from 'react';
import type { Flight } from '../../api/flights';
import { FARE_TYPE_LABELS, TRAVEL_CLASS_LABELS, type FareType, type TravelClass } from '../../api/quotes';
import { seatsApi, type AircraftSeat, type FlightSeatMap } from '../../api/seats';
import { ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { FlightCard } from '../../components/FlightCard';
import { ApiError } from '../../lib/errors';
import { money } from '../../lib/format';
import { SeatMap } from './SeatMap';

/**
 * Choose a seat (FRONTEND_MODULE.md §5 screen 4).
 *
 * <p>Only the cabin the passenger bought is shown, because a fare buys a cabin -
 * offering the whole aircraft would let someone pick a Business seat on an
 * Economy fare and be refused later.
 */
export function SeatSelectionPage({
  flight,
  cabin,
  fare,
  baseFare,
  currency,
  onBack,
  onContinue,
}: {
  flight: Flight;
  cabin: TravelClass;
  fare: FareType;
  baseFare: number;
  currency: string;
  onBack: () => void;
  onContinue?: (seat: AircraftSeat | null) => void;
}) {
  const [map, setMap] = useState<FlightSeatMap | null>(null);
  const [selected, setSelected] = useState<AircraftSeat | null>(null);
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

  const surcharge = selected ? Number(selected.listedSurcharge) || 0 : 0;
  const total = baseFare + surcharge;

  return (
    <main className="mx-auto max-w-3xl px-6 py-10">
      <button
        type="button"
        onClick={onBack}
        className="text-sm font-medium text-brand-700 hover:underline"
      >
        ← Back to fares
      </button>

      <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900">Choose your seat</h1>
      <p className="mt-1 text-sm text-slate-600">
        {TRAVEL_CLASS_LABELS[cabin]} · {FARE_TYPE_LABELS[fare]}
      </p>

      <div className="mt-4">
        <FlightCard flight={flight} />
      </div>

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
              selected={selected?.seatNumber ?? null}
              onSelect={setSelected}
            />

            {/* The running total, so a surcharge is never a surprise later. */}
            <dl className="card text-sm">
              <div className="flex justify-between px-4 py-2">
                <dt className="text-slate-600">Base fare</dt>
                <dd className="tabular text-slate-900">{money(baseFare, currency)}</dd>
              </div>
              <div className="flex justify-between border-t border-slate-100 px-4 py-2">
                <dt className="text-slate-600">
                  Seat {selected ? selected.seatNumber : '(assigned for you)'}
                </dt>
                <dd className="tabular text-slate-900">
                  {surcharge > 0 ? money(surcharge, currency) : 'Free'}
                </dd>
              </div>
              <div className="flex justify-between border-t border-slate-200 px-4 py-2 font-medium">
                <dt className="text-slate-900">Total per passenger</dt>
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
  );
}
