import {
  FARE_TYPE_BLURB,
  FARE_TYPE_LABELS,
  FARE_TYPE_ORDER,
  TRAVEL_CLASS_LABELS,
  TRAVEL_CLASS_ORDER,
  type CabinQuote,
  type FareType,
  type Quote,
  type TravelClass,
} from '../../api/quotes';
import { money } from '../../lib/format';

/**
 * The fare grid (FRONTEND_MODULE.md §5 screen 3, §10.2).
 *
 * <p>This is the densest surface in the app and deliberately so: cabins down,
 * fare types across, prices in tabular figures so a passenger can compare a
 * column at a glance. It is the screen where the real-airline direction earns
 * its keep - a spacious card layout would make the same information take four
 * screens and be harder to compare.
 */
export function FareTable({
  quote,
  onSelect,
}: {
  quote: Quote;
  onSelect?: (cabin: TravelClass, fare: FareType) => void;
}) {
  // Cabins the aircraft actually sells, cheapest first. A cabin missing from the
  // response is not "sold out" - the aircraft has no such cabin - so it is
  // simply absent here rather than shown as unavailable.
  const cabins = TRAVEL_CLASS_ORDER.map((travelClass) =>
    quote.cabins.find((cabin) => cabin.travelClass === travelClass),
  ).filter((cabin): cabin is CabinQuote => cabin !== undefined);

  if (cabins.length === 0) {
    return (
      <p className="rounded border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600">
        No fares are published for this flight.
      </p>
    );
  }

  return (
    <div className="overflow-x-auto rounded border border-slate-200 bg-white">
      <table className="w-full min-w-[34rem] text-sm">
        <caption className="sr-only">Fares by cabin and fare type</caption>
        <thead>
          <tr className="border-b border-slate-200 bg-slate-50 text-left">
            <th scope="col" className="px-4 py-2 font-medium text-slate-700">
              Cabin
            </th>
            {FARE_TYPE_ORDER.map((fareType) => (
              <th key={fareType} scope="col" className="px-4 py-2 font-medium text-slate-700">
                {FARE_TYPE_LABELS[fareType]}
                <span className="block text-xs font-normal text-slate-500">
                  {FARE_TYPE_BLURB[fareType]}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {cabins.map((cabin) => (
            <tr key={cabin.travelClass} className="border-b border-slate-100 last:border-0">
              <th scope="row" className="px-4 py-3 text-left align-top">
                <span className="font-medium text-slate-900">
                  {TRAVEL_CLASS_LABELS[cabin.travelClass]}
                </span>
                <SeatsLeft seats={cabin.availableSeats} />
              </th>

              {FARE_TYPE_ORDER.map((fareType) => {
                const fare = cabin.baseFares[fareType];
                const soldOut = cabin.availableSeats === 0;

                if (fare === undefined || fare === null) {
                  return (
                    <td key={fareType} className="px-4 py-3 align-top text-slate-400">
                      —
                    </td>
                  );
                }

                return (
                  <td key={fareType} className="px-4 py-3 align-top">
                    <button
                      type="button"
                      disabled={soldOut || !onSelect}
                      onClick={() => onSelect?.(cabin.travelClass, fareType)}
                      className="tabular rounded border border-slate-200 px-2.5 py-1.5 font-medium text-slate-900 transition hover:border-brand-400 hover:bg-brand-50 disabled:cursor-not-allowed disabled:border-slate-100 disabled:text-slate-400 disabled:hover:bg-transparent"
                    >
                      {money(fare, quote.currency)}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>

      <p className="border-t border-slate-100 px-4 py-2 text-xs text-slate-500">
        Base fare per passenger. Choosing a specific seat may add a surcharge; letting us assign one
        is free.
      </p>
    </div>
  );
}

/**
 * Seats remaining, with the null case handled honestly.
 *
 * <p>null means there is no inventory record - availability is unknown, not
 * zero. Saying "Sold out" there would be a claim nobody made.
 */
function SeatsLeft({ seats }: { seats: number | null }) {
  if (seats === null) {
    return null;
  }
  if (seats === 0) {
    return <span className="mt-0.5 block text-xs font-medium text-red-600">Sold out</span>;
  }
  // Only shout when it is genuinely scarce; a permanent "42 left" is noise.
  if (seats <= 9) {
    return (
      <span className="mt-0.5 block text-xs font-medium text-amber-700">
        Only {seats} left
      </span>
    );
  }
  return <span className="mt-0.5 block text-xs text-slate-500">{seats} seats</span>;
}
