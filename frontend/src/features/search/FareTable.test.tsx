import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { FareTable } from './FareTable';
import type { Quote } from '../../api/quotes';

/**
 * The fare table is where the API's two easily-flattened distinctions become
 * claims to a passenger, so both are pinned down here.
 */
describe('FareTable', () => {
  afterEach(cleanup);

  const quote = (cabins: Quote['cabins']): Quote => ({
    flightId: 1,
    currency: 'GBP',
    cabins,
  });

  it('omits a cabin the aircraft does not sell, rather than showing it sold out', () => {
    render(
      <FareTable
        quote={quote([
          {
            travelClass: 'ECONOMY',
            availableSeats: 120,
            baseFares: { SAVER: 89, FLEXI: 129, PREMIUM: 169 },
            fromFare: 89,
          },
        ])}
      />,
    );

    // A missing cabin IS the answer "this aircraft has no First class". Showing
    // it greyed out or "sold out" would claim something nobody said.
    expect(screen.getByText('Economy')).toBeDefined();
    expect(screen.queryByText('First')).toBeNull();
    expect(screen.queryByText('Business')).toBeNull();
  });

  it('says nothing about availability when there is no inventory record', () => {
    render(
      <FareTable
        quote={quote([
          {
            travelClass: 'ECONOMY',
            availableSeats: null, // no inventory record: unknown, NOT zero
            baseFares: { SAVER: 89 },
            fromFare: 89,
          },
        ])}
      />,
    );

    // Rendering null as 0 would tell a passenger the flight is full when the
    // server only said it did not know.
    expect(screen.queryByText('Sold out')).toBeNull();
    expect(screen.getByText('£89.00')).toBeDefined();
  });

  it('marks a genuinely empty cabin sold out and disables its fares', () => {
    render(
      <FareTable
        quote={quote([
          {
            travelClass: 'ECONOMY',
            availableSeats: 0,
            baseFares: { SAVER: 89 },
            fromFare: 89,
          },
        ])}
      />,
    );

    expect(screen.getByText('Sold out')).toBeDefined();
    expect(screen.getByRole('button', { name: '£89.00' }).hasAttribute('disabled')).toBe(true);
  });

  it('warns only when seats are genuinely scarce', () => {
    render(
      <FareTable
        quote={quote([
          {
            travelClass: 'ECONOMY',
            availableSeats: 3,
            baseFares: { SAVER: 89 },
            fromFare: 89,
          },
          {
            travelClass: 'BUSINESS',
            availableSeats: 40,
            baseFares: { SAVER: 450 },
            fromFare: 450,
          },
        ])}
      />,
    );

    // A permanent "42 left" is noise and trains people to ignore the warning.
    expect(screen.getByText('Only 3 left')).toBeDefined();
    expect(screen.getByText('40 seats')).toBeDefined();
  });

  it('renders a dash for a fare type this cabin does not offer', () => {
    render(
      <FareTable
        quote={quote([
          {
            travelClass: 'ECONOMY',
            availableSeats: 50,
            baseFares: { SAVER: 89 }, // no FLEXI/PREMIUM
            fromFare: 89,
          },
        ])}
      />,
    );

    expect(screen.getAllByText('—').length).toBe(2);
  });

  it('orders cabins cheapest first', () => {
    render(
      <FareTable
        quote={quote([
          { travelClass: 'BUSINESS', availableSeats: 10, baseFares: { SAVER: 450 }, fromFare: 450 },
          { travelClass: 'ECONOMY', availableSeats: 10, baseFares: { SAVER: 89 }, fromFare: 89 },
        ])}
      />,
    );

    // Response order must not leak into the UI - a fare table reads cheapest
    // first regardless of how the server happened to serialise it.
    const rowHeaders = screen.getAllByRole('rowheader').map((cell) => cell.textContent);
    expect(rowHeaders[0]).toContain('Economy');
    expect(rowHeaders[1]).toContain('Business');
  });
});
