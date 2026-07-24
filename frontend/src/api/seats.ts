import { api } from './client';
import type { TravelClass } from './quotes';

/**
 * Seat maps (FRONTEND_MODULE.md §5 screen 4).
 *
 * <p>A seat map takes THREE calls, because the data is split the way the domain
 * is:
 * <ol>
 *   <li>flight inventory → which aircraft is flying it;</li>
 *   <li>the aircraft's seat map → the physical cabin: rows, positions, exit
 *       rows, and each seat's listed surcharge;</li>
 *   <li>this flight's reservations → which of those seats are already taken.</li>
 * </ol>
 *
 * <p>The split matters: the seat map belongs to the <b>aircraft</b>, so its
 * {@code status} describes the seat itself (broken, blocked), <b>not</b> whether
 * anyone has it on this particular flight. Reading that field as "available"
 * would draw every taken seat as free.
 *
 * <p><b>Holds are deliberately not visible.</b> A seat someone is part-way
 * through booking is held, not reserved, and no endpoint exposes holds - so it
 * will look free here and the booking call will answer 409. That is not a bug to
 * paper over: the platform resolves the race correctly (certified by the
 * double-sell e2e case), and the UI's job is to lose that race gracefully.
 */

export type SeatPosition = 'WINDOW' | 'MIDDLE' | 'AISLE';
export type AircraftSeatStatus = 'ACTIVE' | 'BLOCKED' | 'INACTIVE';

export interface AircraftSeat {
  id: number;
  seatNumber: string;
  rowNumber: number;
  seatType: TravelClass;
  position: SeatPosition;
  /** The seat's own condition on the aircraft - NOT per-flight occupancy. */
  status: AircraftSeatStatus;
  exitRow: boolean;
  /** What CHOOSING this seat costs. Auto-assignment is always free. */
  listedSurcharge: string | number;
}

export interface SeatMap {
  aircraftId: number;
  registrationNumber: string;
  model: string;
  totalSeats: number;
  seats: AircraftSeat[];
}

export interface FlightInventory {
  flightId: number;
  aircraftId: number;
  totalSeats: number;
  availableSeats: number;
}

export interface SeatReservation {
  seatNumber: string;
}

/** A seat map for one flight, with per-flight occupancy folded in. */
export interface FlightSeatMap {
  aircraft: SeatMap;
  /** Seat numbers already reserved on this flight. */
  taken: Set<string>;
}

export const seatsApi = {
  inventoryFor(flightId: number, signal?: AbortSignal): Promise<FlightInventory> {
    return api.get<FlightInventory>(`/api/inventory/flight/${flightId}`, { signal });
  },

  seatMap(aircraftId: number, signal?: AbortSignal): Promise<SeatMap> {
    return api.get<SeatMap>(`/api/aircraft/${aircraftId}/seat-map`, { signal });
  },

  reservations(flightId: number, signal?: AbortSignal): Promise<SeatReservation[]> {
    return api.get<SeatReservation[]>(`/api/reservations/flight/${flightId}`, { signal });
  },

  /**
   * Everything needed to draw one flight's cabin.
   *
   * <p>The three calls run in parallel where possible; the seat map has to wait
   * for the inventory record, since that is what names the aircraft.
   */
  async forFlight(flightId: number, signal?: AbortSignal): Promise<FlightSeatMap> {
    const inventory = await seatsApi.inventoryFor(flightId, signal);
    const [aircraft, reservations] = await Promise.all([
      seatsApi.seatMap(inventory.aircraftId, signal),
      seatsApi.reservations(flightId, signal),
    ]);

    return {
      aircraft,
      taken: new Set(reservations.map((reservation) => reservation.seatNumber)),
    };
  },
};

/** Seats grouped into rows, in cabin order - the shape a seat map is drawn in. */
export function toRows(seats: AircraftSeat[]): Array<{ row: number; seats: AircraftSeat[] }> {
  const byRow = new Map<number, AircraftSeat[]>();
  for (const seat of seats) {
    const row = byRow.get(seat.rowNumber);
    if (row) {
      row.push(seat);
    } else {
      byRow.set(seat.rowNumber, [seat]);
    }
  }

  return [...byRow.entries()]
    .sort(([a], [b]) => a - b)
    .map(([row, rowSeats]) => ({
      row,
      // Seat letter order, so 1A 1B 1C reads left to right as it does on board.
      seats: rowSeats.sort((a, b) => a.seatNumber.localeCompare(b.seatNumber)),
    }));
}
