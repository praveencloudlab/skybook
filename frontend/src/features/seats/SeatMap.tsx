import { toRows, type AircraftSeat, type FlightSeatMap } from '../../api/seats';
import { TRAVEL_CLASS_LABELS, type TravelClass } from '../../api/quotes';
import { money } from '../../lib/format';

/**
 * The cabin (FRONTEND_MODULE.md §5 screen 4, §10.2).
 *
 * <p>Drawn as an actual aircraft cabin, nose at the top, so orientation is
 * obvious at a glance: which way is forward, where the aisle is, which rows sit
 * at an exit. A passenger reasons about a seat spatially - window or aisle, how
 * far forward, exit row, price - and a bare grid of seat numbers forces them to
 * rebuild all of that in their head. The fuselage frame + FRONT marker is what
 * makes it read as a plane rather than a spreadsheet.
 */
export function SeatMap({
  map,
  cabin,
  currency,
  paxCount,
  selected,
  onToggle,
  onClear,
}: {
  map: FlightSeatMap;
  /** Only this cabin is selectable - a fare buys a cabin. */
  cabin: TravelClass;
  currency: string;
  /** How many travellers - the selection capacity, one seat each. */
  paxCount: number;
  /** Chosen seats in passenger order (index 0 = passenger 1). */
  selected: AircraftSeat[];
  onToggle: (seat: AircraftSeat) => void;
  onClear: () => void;
}) {
  const cabinSeats = map.aircraft.seats.filter((seat) => seat.seatType === cabin);
  const rows = toRows(cabinSeats);

  if (rows.length === 0) {
    return (
      <p className="card px-3 py-2 text-sm text-slate-600">
        This aircraft has no {TRAVEL_CLASS_LABELS[cabin]} cabin.
      </p>
    );
  }

  // The widest row is the template for the column-letter header and aisle
  // positions - cabins are near-uniform, so this aligns the header to the grid.
  const templateRow = rows.reduce((widest, r) => (r.seats.length > widest.seats.length ? r : widest), rows[0]);

  return (
    <div className="space-y-4">
      <Legend />

      <div className="card overflow-x-auto px-4 py-6">
        <div className="mx-auto w-fit">
          {/* Nose / flight deck - the unmistakable "this end is the front". */}
          <div className="relative mx-auto mb-1 flex h-14 w-[68%] min-w-[200px] items-end justify-center">
            <div className="absolute inset-x-0 bottom-0 top-3 rounded-t-[999px] border-2 border-b-0 border-slate-200 bg-gradient-to-b from-slate-100 to-white" />
            <div className="relative mb-1.5 flex flex-col items-center gap-0.5 text-slate-500">
              <svg viewBox="0 0 24 24" className="h-4 w-4 fill-brand-600" aria-hidden="true">
                <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
              </svg>
              <span className="text-[10px] font-semibold uppercase tracking-[0.15em]">Front of aircraft</span>
            </div>
          </div>

          {/* Fuselage: the seat grid inside a rounded cabin wall. */}
          <div className="rounded-3xl border-2 border-slate-200 bg-slate-50/60 px-3 py-3">
            {/* Column letters, aligned to the seats below. */}
            <div className="mb-2 flex items-center gap-1.5">
              <span className="w-6" aria-hidden="true" />
              {templateRow.seats.map((seat, index) => {
                const previous = templateRow.seats[index - 1];
                const aisleBefore = previous?.position === 'AISLE' && seat.position === 'AISLE';
                const letter = seat.seatNumber.replace(/^\d+/, '');
                return (
                  <div key={seat.seatNumber} className="flex items-center">
                    {aisleBefore ? <span className="w-6" aria-hidden="true" /> : null}
                    <span className="w-9 text-center text-[11px] font-semibold text-slate-400">{letter}</span>
                  </div>
                );
              })}
              <span className="w-6" aria-hidden="true" />
            </div>

            <div className="space-y-1.5">
              {rows.map(({ row, seats }) => {
                const exitRow = seats.some((s) => s.exitRow);
                return (
                  <div key={row} className="flex items-center gap-1.5">
                    <span className="w-6 text-right text-xs tabular-nums text-slate-400">{row}</span>

                    {seats.map((seat, index) => {
                      // A gap where the aisle is: two adjacent AISLE positions
                      // mark the walkway. Without it a 3-3 cabin reads as one block.
                      const previous = seats[index - 1];
                      const aisleBefore = previous?.position === 'AISLE' && seat.position === 'AISLE';
                      return (
                        <div key={seat.seatNumber} className="flex items-center">
                          {aisleBefore ? <span className="w-6" aria-hidden="true" /> : null}
                          <Seat
                            seat={seat}
                            currency={currency}
                            taken={map.taken.has(seat.seatNumber)}
                            passengerIndex={selected.findIndex((s) => s.seatNumber === seat.seatNumber)}
                            multi={paxCount > 1}
                            onSelect={onToggle}
                          />
                        </div>
                      );
                    })}

                    {/* Exit-door tab on exit rows - the real cabin cue for the row. */}
                    <span
                      className={
                        'ml-1 w-6 text-center text-[8px] font-bold uppercase tracking-tight ' +
                        (exitRow ? 'text-emerald-600' : 'text-transparent')
                      }
                      aria-hidden="true"
                    >
                      Exit
                    </span>
                  </div>
                );
              })}
            </div>
          </div>

          {/* A hint of tail so the body reads as a whole aircraft. */}
          <div className="mx-auto mt-0 h-4 w-[55%] min-w-[160px] rounded-b-3xl border-2 border-t-0 border-slate-200 bg-gradient-to-b from-slate-50 to-transparent" />
          <p className="mt-1 text-center text-[10px] uppercase tracking-[0.15em] text-slate-400">Rear of aircraft</p>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-sm">
        <span className="flex flex-wrap items-center gap-1.5 text-slate-700">
          {selected.length === 0 ? (
            paxCount > 1 ? 'No seats chosen' : 'No seat chosen'
          ) : (
            <>
              {selected.map((seat, i) => (
                <span
                  key={seat.seatNumber}
                  className="tabular inline-flex items-center gap-1 rounded-full bg-white px-2 py-0.5 text-xs font-semibold text-slate-800 ring-1 ring-inset ring-slate-200"
                >
                  {paxCount > 1 ? <span className="text-brand-600">P{i + 1}</span> : null}
                  {seat.seatNumber}
                </span>
              ))}
              {selected.length < paxCount ? (
                <span className="text-xs text-slate-500">
                  {paxCount - selected.length} more assigned free at check-in
                </span>
              ) : null}
            </>
          )}
        </span>
        {/*
          Skipping must be as easy as choosing, and visibly free: auto-assignment
          costs nothing, and a passenger who does not care about the window
          should not feel they are giving something up.
        */}
        <button
          type="button"
          onClick={onClear}
          className="text-sm font-medium text-brand-700 hover:underline"
        >
          Skip — assign {paxCount > 1 ? 'our seats' : 'me a seat'} (free)
        </button>
      </div>
    </div>
  );
}

function Seat({
  seat,
  currency,
  taken,
  passengerIndex,
  multi,
  onSelect,
}: {
  seat: AircraftSeat;
  currency: string;
  taken: boolean;
  /** Which traveller holds this seat (0-based), or -1 when unselected. */
  passengerIndex: number;
  /** Whether the party has more than one traveller (shows P1/P2 badges). */
  multi: boolean;
  onSelect: (seat: AircraftSeat) => void;
}) {
  // Blocked/inactive are the AIRCRAFT's own condition; taken is this flight's.
  // Both make a seat unpickable, but for different reasons.
  const unavailable = taken || seat.status !== 'ACTIVE';
  const selected = passengerIndex >= 0;
  const surcharge = Number(seat.listedSurcharge) || 0;

  const label = [
    `Seat ${seat.seatNumber}`,
    seat.position.toLowerCase(),
    seat.exitRow ? 'exit row' : null,
    selected ? `passenger ${passengerIndex + 1}` : null,
    unavailable ? 'unavailable' : surcharge > 0 ? `plus ${money(surcharge, currency)}` : 'no extra charge',
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <button
      type="button"
      disabled={unavailable}
      onClick={() => onSelect(seat)}
      title={label}
      aria-label={label}
      aria-pressed={selected}
      className={
        // rounded-t-lg + a squared base reads as a seat (backrest + cushion),
        // not a rounded chip - a small cue that adds up across a cabin.
        'relative h-9 w-9 rounded-t-lg rounded-b-sm text-[11px] font-medium transition ' +
        (unavailable
          ? 'cursor-not-allowed bg-slate-100 text-slate-300'
          : selected
            ? 'bg-brand-600 text-white ring-2 ring-brand-300'
            : surcharge > 0
              ? 'bg-amber-50 text-amber-900 hover:bg-amber-100 ring-1 ring-inset ring-amber-200'
              : 'bg-emerald-50 text-emerald-900 hover:bg-emerald-100 ring-1 ring-inset ring-emerald-200')
      }
    >
      {selected && multi ? (
        <span className="absolute -top-2 left-1/2 -translate-x-1/2 rounded-full bg-brand-600 px-1 text-[8px] font-bold leading-3 text-white ring-2 ring-white">
          P{passengerIndex + 1}
        </span>
      ) : null}
      {seat.seatNumber.replace(/^\d+/, '')}
      {seat.exitRow && !unavailable ? (
        <span
          aria-hidden="true"
          className="absolute -top-0.5 -right-0.5 h-1.5 w-1.5 rounded-full bg-emerald-500"
        />
      ) : null}
    </button>
  );
}

function Legend() {
  return (
    <div className="flex flex-wrap items-center gap-4 text-xs text-slate-600">
      <Swatch className="bg-emerald-50 ring-1 ring-inset ring-emerald-200">Free</Swatch>
      <Swatch className="bg-amber-50 ring-1 ring-inset ring-amber-200">Extra charge</Swatch>
      <Swatch className="bg-brand-600">Selected</Swatch>
      <Swatch className="bg-slate-100">Unavailable</Swatch>
      <span className="flex items-center gap-1.5">
        <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" aria-hidden="true" />
        Exit row
      </span>
    </div>
  );
}

function Swatch({ className, children }: { className: string; children: React.ReactNode }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className={`h-3.5 w-3.5 rounded ${className}`} aria-hidden="true" />
      {children}
    </span>
  );
}
