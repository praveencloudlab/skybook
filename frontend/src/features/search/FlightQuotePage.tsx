import { useEffect, useState } from 'react';
import type { Flight } from '../../api/flights';
import { quotesApi, type FareType, type Quote, type TravelClass } from '../../api/quotes';
import { ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { FlightCard } from '../../components/FlightCard';
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
    <main className="mx-auto max-w-3xl px-6 py-10">
      <button
        type="button"
        onClick={onBack}
        className="text-sm font-medium text-brand-700 hover:underline"
      >
        ← Back to results
      </button>

      <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900">
        {flight.originAirportCode} → {flight.destinationAirportCode}
      </h1>

      <div className="mt-4">
        <FlightCard flight={flight} />
      </div>

      <h2 className="mt-8 text-sm font-medium text-slate-700">Choose a fare</h2>

      <div className="mt-3 space-y-3">
        <ErrorAlert error={error} />

        {busy ? (
          <p className="card px-3 py-2 text-sm text-slate-500">
            Loading fares…
          </p>
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
  );
}
