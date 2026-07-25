import type { Flight } from '../../api/flights';
import {
  airlineCounts,
  bucketCounts,
  initialFilters,
  TIME_BUCKETS,
  type FilterState,
  type SortKey,
} from './filters';

/**
 * The left filter rail (FRONTEND_MODULE.md §10.2 - Skyscanner-shaped results).
 *
 * <p>Filters are derived from the actual result set, not a fixed list: the
 * airlines shown are the ones that fly this route today, with their counts, so
 * the rail never offers a filter that would empty the list for no reason.
 */
const SORTS: Array<{ id: SortKey; label: string }> = [
  { id: 'earliest', label: 'Earliest' },
  { id: 'latest', label: 'Latest' },
  { id: 'shortest', label: 'Shortest' },
];

export function SearchFilters({
  results,
  state,
  onChange,
}: {
  results: Flight[];
  state: FilterState;
  onChange: (next: FilterState) => void;
}) {
  const airlines = airlineCounts(results);
  const perBucket = bucketCounts(results);
  const dirty =
    state.sort !== 'earliest' ||
    TIME_BUCKETS.some((bucket) => !state.buckets[bucket.id]) ||
    airlines.some((airline) => state.airlines[airline.code] === false);

  return (
    <div className="card space-y-6 p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-900">Filters</h2>
        {dirty ? (
          <button
            type="button"
            onClick={() => onChange(initialFilters(results))}
            className="text-xs font-medium text-brand-700 hover:underline"
          >
            Reset
          </button>
        ) : null}
      </div>

      {/* Sort */}
      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Sort by</h3>
        <div className="grid grid-cols-3 gap-1 rounded-xl bg-slate-100 p-1">
          {SORTS.map((sort) => (
            <button
              key={sort.id}
              type="button"
              onClick={() => onChange({ ...state, sort: sort.id })}
              aria-pressed={state.sort === sort.id}
              className={
                'rounded-lg px-2 py-1.5 text-xs font-medium transition ' +
                (state.sort === sort.id
                  ? 'bg-white text-brand-700 shadow-sm'
                  : 'text-slate-500 hover:text-slate-700')
              }
            >
              {sort.label}
            </button>
          ))}
        </div>
      </div>

      {/* Departure time */}
      <div>
        <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Departure time
        </h3>
        <div className="space-y-1.5">
          {TIME_BUCKETS.map((bucket) => (
            <label
              key={bucket.id}
              className={
                'flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm ' +
                (perBucket[bucket.id] === 0 ? 'opacity-40' : 'hover:bg-slate-50')
              }
            >
              <input
                type="checkbox"
                checked={state.buckets[bucket.id]}
                disabled={perBucket[bucket.id] === 0}
                onChange={(event) =>
                  onChange({
                    ...state,
                    buckets: { ...state.buckets, [bucket.id]: event.target.checked },
                  })
                }
                className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500/40"
              />
              <span className="flex-1 font-medium text-slate-700">{bucket.label}</span>
              <span className="tabular text-xs text-slate-400">{bucket.range}</span>
            </label>
          ))}
        </div>
      </div>

      {/* Airlines */}
      <div>
        <div className="mb-2 flex items-center justify-between">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Airlines</h3>
          <div className="flex gap-2 text-[11px] font-medium text-brand-700">
            <button
              type="button"
              className="hover:underline"
              onClick={() =>
                onChange({
                  ...state,
                  airlines: Object.fromEntries(airlines.map((a) => [a.code, true])),
                })
              }
            >
              All
            </button>
            <span className="text-slate-300">·</span>
            <button
              type="button"
              className="hover:underline"
              onClick={() =>
                onChange({
                  ...state,
                  airlines: Object.fromEntries(airlines.map((a) => [a.code, false])),
                })
              }
            >
              None
            </button>
          </div>
        </div>
        <div className="space-y-1.5">
          {airlines.map((airline) => (
            <label
              key={airline.code}
              className="flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm hover:bg-slate-50"
            >
              <input
                type="checkbox"
                checked={state.airlines[airline.code] ?? true}
                onChange={(event) =>
                  onChange({
                    ...state,
                    airlines: { ...state.airlines, [airline.code]: event.target.checked },
                  })
                }
                className="h-4 w-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500/40"
              />
              <span className="grid h-6 w-8 shrink-0 place-items-center rounded bg-brand-600 text-[10px] font-bold text-white">
                {airline.code}
              </span>
              <span className="tabular flex-1 font-medium text-slate-700">{airline.code}</span>
              <span className="tabular text-xs text-slate-400">{airline.count}</span>
            </label>
          ))}
        </div>
      </div>
    </div>
  );
}
