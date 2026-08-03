import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SeatMap } from './SeatMap';
import type { AircraftSeat, FlightSeatMap } from '../../api/seats';

function seat(overrides: Partial<AircraftSeat> & { seatNumber: string }): AircraftSeat {
  return {
    id: Math.random(),
    rowNumber: Number(overrides.seatNumber.match(/^\d+/)?.[0] ?? 1),
    seatType: 'ECONOMY',
    position: 'WINDOW',
    status: 'ACTIVE',
    exitRow: false,
    listedSurcharge: 0,
    ...overrides,
  };
}

function map(seats: AircraftSeat[], taken: string[] = []): FlightSeatMap {
  return {
    aircraft: {
      aircraftId: 1,
      registrationNumber: 'G-TEST',
      model: '777',
      totalSeats: seats.length,
      seats,
    },
    taken: new Set(taken),
  };
}

describe('SeatMap', () => {
  afterEach(cleanup);

  it('disables a seat reserved on THIS flight, even though the aircraft says ACTIVE', () => {
    // The seat map belongs to the aircraft, so its status describes the seat
    // itself, not who has it today. Reading that field as availability would
    // draw every taken seat as free and send passengers into a 409.
    render(
      <SeatMap
        map={map([seat({ seatNumber: '10A', status: 'ACTIVE' })], ['10A'])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/Seat 10A/).hasAttribute('disabled')).toBe(true);
  });

  it('disables a seat the aircraft has blocked, even when nobody has reserved it', () => {
    render(
      <SeatMap
        map={map([seat({ seatNumber: '10B', status: 'BLOCKED' })])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/Seat 10B/).hasAttribute('disabled')).toBe(true);
  });

  it('shows only the cabin the fare bought', () => {
    render(
      <SeatMap
        map={map([
          seat({ seatNumber: '1A', seatType: 'FIRST' }),
          seat({ seatNumber: '30A', seatType: 'ECONOMY' }),
        ])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    // Offering the whole aircraft would let someone pick a First seat on an
    // Economy fare and be refused later.
    expect(screen.getByLabelText(/Seat 30A/)).toBeDefined();
    expect(screen.queryByLabelText(/Seat 1A/)).toBeNull();
  });

  it('announces the surcharge in the accessible label, in the right currency', () => {
    render(
      <SeatMap
        map={map([seat({ seatNumber: '12F', listedSurcharge: 15, position: 'AISLE' })])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    // Price and position must reach a screen-reader user too - colour alone
    // cannot convey "this one costs extra". Prices now render in the viewer's
    // DISPLAY currency (GBP by default), converted from the source currency
    // at the fixed reference rate - USD 15.00 / 1.27 = £11.81.
    const label = screen.getByLabelText(/Seat 12F/).getAttribute('aria-label');
    expect(label).toContain('aisle');
    expect(label).toContain('£11.81');
  });

  it('says a free seat is free rather than silently saying nothing', () => {
    render(
      <SeatMap
        map={map([seat({ seatNumber: '20C', listedSurcharge: 0 })])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    expect(screen.getByLabelText(/Seat 20C/).getAttribute('aria-label')).toContain(
      'no extra charge',
    );
  });

  it('offers skipping as a free, equally prominent choice', () => {
    const onClear = vi.fn();
    render(
      <SeatMap
        map={map([seat({ seatNumber: '20C' })])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={onClear}
      />,
    );

    // Auto-assignment genuinely costs nothing; a passenger who does not care
    // should not feel they are giving something up.
    const skip = screen.getByRole('button', { name: /Skip/ });
    expect(skip.textContent).toContain('free');
    skip.click();
    expect(onClear).toHaveBeenCalled();
  });

  it('badges each chosen seat with its passenger when a party travels', () => {
    render(
      <SeatMap
        map={map([seat({ seatNumber: '10A' }), seat({ seatNumber: '10B', position: 'MIDDLE' })])}
        cabin="ECONOMY"
        currency="USD"
        paxCount={2}
        selected={[seat({ seatNumber: '10B', position: 'MIDDLE' })]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    // The map must say WHOSE seat 10B is - two travellers picking from one map
    // otherwise cannot tell their selections apart.
    expect(screen.getByLabelText(/Seat 10B/).getAttribute('aria-label')).toContain('passenger 1');
    expect(screen.getByText(/1 more assigned free at check-in/)).toBeDefined();
  });

  it('explains an absent cabin instead of rendering an empty map', () => {
    render(
      <SeatMap
        map={map([seat({ seatNumber: '30A', seatType: 'ECONOMY' })])}
        cabin="FIRST"
        currency="USD"
        paxCount={1}
        selected={[]}
        onToggle={vi.fn()}
        onClear={vi.fn()}
      />,
    );

    expect(screen.getByText(/no First cabin/i)).toBeDefined();
  });
});
