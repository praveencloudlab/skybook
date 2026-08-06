import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GuestCheckInPage } from './GuestCheckInPage';
import { guestApi } from '../../api/guest';
import { bookingsApi } from '../../api/bookings';
import { checkinApi } from '../../api/checkin';
import { ApiError } from '../../lib/errors';

vi.mock('../../api/guest', () => ({
  guestApi: { start: vi.fn(), end: vi.fn(), emailBoardingPass: vi.fn() },
}));
vi.mock('../../api/bookings', () => ({ bookingsApi: { byId: vi.fn() } }));
vi.mock('../../api/checkin', () => ({
  checkinApi: { forBooking: vi.fn(), checkIn: vi.fn(), boardingPass: vi.fn() },
}));

const start = vi.mocked(guestApi.start);
const end = vi.mocked(guestApi.end);
const emailPass = vi.mocked(guestApi.emailBoardingPass);
const byId = vi.mocked(bookingsApi.byId);
const forBooking = vi.mocked(checkinApi.forBooking);
const boardingPass = vi.mocked(checkinApi.boardingPass);

function checkInRecord(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    bookingId: 41,
    bookingReference: 'SKY41X',
    bookingPassengerId: 3,
    flightId: 2,
    flightNumber: 'BA277',
    originAirportCode: 'LHR',
    destinationAirportCode: 'DXB',
    departureTime: '2026-08-20T08:25:00',
    passengerName: 'Richards Varma',
    seatNumber: '12A',
    travelClass: 'ECONOMY',
    status: 'OPEN',
    ...overrides,
  } as never;
}

async function lookup() {
  render(
    <MemoryRouter>
      <GuestCheckInPage />
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText('Booking reference'), { target: { value: 'SKY41X' } });
  fireEvent.change(screen.getByLabelText('Last name'), { target: { value: 'Varma' } });
  fireEvent.click(screen.getByRole('button', { name: /find my booking/i }));
}

describe('guest check-in (GUEST_CHECKIN_MODULE.md §7)', () => {
  beforeEach(() => {
    byId.mockResolvedValue({ bookingReference: 'SKY41X' } as never);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('exchanges reference and surname for a session and shows the trip', async () => {
    start.mockResolvedValue({ bookingId: 41 });
    forBooking.mockResolvedValue([checkInRecord()]);

    await lookup();

    expect(await screen.findByText('Richards Varma')).toBeDefined();
    expect(start).toHaveBeenCalledWith('SKY41X', 'Varma');
  });

  it('says one generic thing for every wrong-input shape', async () => {
    // The server refuses to reveal WHICH part was wrong; the UI must not
    // undo that by guessing.
    start.mockRejectedValue(new ApiError('notFound', 404, 'nope'));

    await lookup();

    expect(
      await screen.findByText("We couldn't find a booking matching those details."),
    ).toBeDefined();
  });

  it('offers no owner-only actions anywhere on the page', async () => {
    // Not hidden - absent. The guest page simply does not contain them.
    start.mockResolvedValue({ bookingId: 41 });
    forBooking.mockResolvedValue([checkInRecord({ status: 'CHECKED_IN' })]);
    boardingPass.mockResolvedValue({
      id: 1, checkInId: 7, boardingPassNumber: 'BP1', token: 'tok',
      bookingReference: 'SKY41X', passengerName: 'Richards Varma', seatNumber: '12A',
    } as never);

    await lookup();
    await screen.findByText('Richards Varma');

    expect(screen.queryByText(/cancel booking/i)).toBeNull();
    expect(screen.queryByText(/change flight/i)).toBeNull();
    expect(screen.queryByText(/payment/i)).toBeNull();
  });

  it('emails the pass to a chosen address once checked in', async () => {
    start.mockResolvedValue({ bookingId: 41 });
    forBooking.mockResolvedValue([checkInRecord({ status: 'CHECKED_IN' })]);
    boardingPass.mockResolvedValue({
      id: 1, checkInId: 7, boardingPassNumber: 'BP1', token: 'tok',
      bookingReference: 'SKY41X', passengerName: 'Richards Varma', seatNumber: '12A',
    } as never);
    emailPass.mockResolvedValue();

    await lookup();
    const emailField = await screen.findByLabelText('Email my boarding pass to');
    fireEvent.change(emailField, { target: { value: 'traveller@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: /^send$/i }));

    await waitFor(() =>
      expect(emailPass).toHaveBeenCalledWith(7, 'traveller@example.com'),
    );
    expect(await screen.findByText('Your boarding pass is on its way.')).toBeDefined();
  });

  it('ends the session explicitly on Done', async () => {
    // Shared airport computers must not depend on tab-closing: the cookie is
    // httpOnly, so only a server call can clear it.
    start.mockResolvedValue({ bookingId: 41 });
    forBooking.mockResolvedValue([checkInRecord()]);
    end.mockResolvedValue();

    await lookup();
    fireEvent.click(await screen.findByRole('button', { name: /done/i }));

    await waitFor(() => expect(end).toHaveBeenCalled());
    expect(await screen.findByText('You have been signed out of this booking.')).toBeDefined();
    expect(screen.getByLabelText('Booking reference')).toBeDefined();
  });
});
