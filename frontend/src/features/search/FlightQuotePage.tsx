import { useEffect, useState } from 'react';
import type { Flight } from '../../api/flights';
import { quotesApi, type FareType, type Quote, type TravelClass } from '../../api/quotes';
import { ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { TripSummaryBar } from '../../components/TripSummaryBar';
import { ApiError } from '../../lib/errors';
import { FareTable } from './FareTable';

/**
 * A chosen flight and its fares (FRONTEND_MODULE.md §5 screen 3).
 *
 * <p>The quote is fetched per flight rather than folded into search results:
 * booking-service is the only place inventory availability and base fares meet,
 * and asking for all of them up front would mean a quote request per result row.
 */
export function FlightQuotePage({
  flight,
  onBack,
  onChoose,
}: {
  flight: Flight;
  onBack: () => void;
  // Carries the PRICE, not just the labels: the seat screen shows a running
  // total, and re-deriving the fare there would mean a second quote call that
  // could disagree with the one the passenger actually clicked.
  onChoose?: (choice: {
    cabin: TravelClass;
    fare: FareType;
    baseFare: number;
    currency: string;
  }) => void;
}) {
  const [quote, setQuote] = useState<Quote | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setError(null);

    quotesApi
      .forFlight(flight.id, controller.signal)
      .then((result) => setQuote(result))
      .catch((cause) => {
        // An abort is us navigating away, not a failure worth reporting.
        if (cause instanceof DOMException && cause.name === 'AbortError') {
          return;
        }
        setError(cause instanceof ApiError ? cause : null);
      })
      .finally(() => setBusy(false));

    return () => controller.abort();
  }, [flight.id]);

  return (
    <>
      <TripSummaryBar flight={flight} step="fares" onBack={onBack} />

      <main className="mx-auto max-w-5xl px-6 py-8">
        <div className="max-w-xl">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Choose your fare</h1>
          <p className="mt-1 text-sm text-slate-500">
            Pick a cabin and fare. Every fare is the base price per passenger — you'll choose a seat
            next, and letting us assign one is free.
          </p>
        </div>

        <div className="mt-6 space-y-3">
          <ErrorAlert error={error} />

          {busy ? (
            <div className="card px-4 py-8 text-center text-sm text-slate-500">
              <span className="inline-flex items-center gap-2">
                <span className="h-4 w-4 animate-spin rounded-full border-2 border-brand-300 border-t-transparent" />
                Loading live fares…
              </span>
            </div>
          ) : quote ? (
            <FareTable
              quote={quote}
              onSelect={
                onChoose
                  ? (cabin, fare) => {
                      const chosen = quote.cabins.find((c) => c.travelClass === cabin);
                      const baseFare = Number(chosen?.baseFares[fare] ?? 0);
                      onChoose({ cabin, fare, baseFare, currency: quote.currency });
                    }
                  : undefined
              }
            />
          ) : null}

          {error?.retryable ? (
            <Button variant="secondary" onClick={() => setQuote(null)}>
              Try again
            </Button>
          ) : null}
        </div>
      </main>
    </>
  );
}
