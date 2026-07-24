import { toRows, type AircraftSeat, type FlightSeatMap } from '../../api/seats';
import { TRAVEL_CLASS_LABELS, type TravelClass } from '../../api/quotes';
import { money } from '../../lib/format';

/**
 * The cabin (FRONTEND_MODULE.md §5 screen 4, §10.2).
 *
 * <p>Drawn as an actual seat map rather than a dropdown, because the information
 * that makes a seat worth choosing - window or aisle, how far forward, whether
 * it is an exit row, what it costs - is spatial. A list of seat numbers with
 * prices makes a passenger do that reconstruction in their head.
 */
export function SeatMap({
  map,
  cabin,
  currency,
  selected,
  onSelect,
}: {
  map: FlightSeatMap;
  /** Only this cabin is selectable - a fare buys a cabin. */
  cabin: TravelClass;
  currency: string;
  selected: string | null;
  onSelect: (seat: AircraftSeat | null) => void;
}) {
  const cabinSeats = map.aircraft.seats.filter((seat) => seat.seatType === cabin);
  const rows = toRows(cabinSeats);

  if (rows.length === 0) {
    return (
      <p className="rounded border border-slate-200 bg-white px-3 py-2 text-sm text-slate-600">
        This aircraft has no {TRAVEL_CLASS_LABELS[cabin]} cabin.
      </p>
    );
  }

  return (
    <div className="space-y-4">
      <Legend />

      <div className="overflow-x-auto rounded border border-slate-200 bg-white p-4">
        <div className="mx-auto w-fit space-y-1.5">
          {rows.map(({ row, seats }) => (
            <div key={row} className="flex items-center gap-1.5">
              <span className="w-6 text-right text-xs tabular-nums text-slate-400">{row}</span>

              {seats.map((seat, index) => {
                // A gap where the aisle is: seats either side of a position
                // change from AISLE to AISLE mark the walkway. Without it a
                // 3-3-3 cabin reads as one undifferentiated block.
                const previous = seats[index - 1];
                const aisleBefore =
                  previous?.position === 'AISLE' && seat.position === 'AISLE';

                return (
                  <div key={seat.seatNumber} className="flex items-center">
                    {aisleBefore ? <span className="w-6" aria-hidden="true" /> : null}
                    <Seat
                      seat={seat}
                      currency={currency}
                      taken={map.taken.has(seat.seatNumber)}
                      selected={selected === seat.seatNumber}
                      onSelect={onSelect}
                    />
                  </div>
                );
              })}
            </div>
          ))}
        </div>
      </div>

      <div className="flex items-center justify-between rounded border border-slate-200 bg-slate-50 px-3 py-2 text-sm">
        <span className="text-slate-700">
          {selected ? (
            <>
              Seat <span className="font-medium text-slate-900">{selected}</span> selected
            </>
          ) : (
            'No seat chosen'
          )}
        </span>
        {/*
          Skipping must be as easy as choosing, and visibly free: auto-assignment
          costs nothing, and a passenger who does not care about the window
          should not feel they are giving something up.
        */}
        <button
          type="button"
          onClick={() => onSelect(null)}
          className="text-sm font-medium text-brand-700 hover:underline"
        >
          Skip — assign me a seat (free)
        </button>
      </div>
    </div>
  );
}

function Seat({
  seat,
  currency,
  taken,
  selected,
  onSelect,
}: {
  seat: AircraftSeat;
  currency: string;
  taken: boolean;
  selected: boolean;
  onSelect: (seat: AircraftSeat) => void;
}) {
  // Blocked/inactive are the AIRCRAFT's own condition; taken is this flight's.
  // Both make a seat unpickable, but for different reasons.
  const unavailable = taken || seat.status !== 'ACTIVE';
  const surcharge = Number(seat.listedSurcharge) || 0;

  const label = [
    `Seat ${seat.seatNumber}`,
    seat.position.toLowerCase(),
    seat.exitRow ? 'exit row' : null,
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
        'relative h-9 w-9 rounded text-[11px] font-medium transition ' +
        (unavailable
          ? 'cursor-not-allowed bg-slate-100 text-slate-300'
          : selected
            ? 'bg-brand-600 text-white ring-2 ring-brand-300'
            : surcharge > 0
              ? 'bg-amber-50 text-amber-900 hover:bg-amber-100 ring-1 ring-inset ring-amber-200'
              : 'bg-emerald-50 text-emerald-900 hover:bg-emerald-100 ring-1 ring-inset ring-emerald-200')
      }
    >
      {seat.seatNumber.replace(/^\d+/, '')}
      {seat.exitRow && !unavailable ? (
        <span
          aria-hidden="true"
          className="absolute -top-0.5 -right-0.5 h-1.5 w-1.5 rounded-full bg-brand-500"
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
        <span className="h-1.5 w-1.5 rounded-full bg-brand-500" aria-hidden="true" />
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
