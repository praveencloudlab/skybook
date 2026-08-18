/**
 * Passenger-facing policy figures - the numbers quoted in cancellation
 * dialogs, ticket conditions pages and check-in screens. Each block
 * mirrors the backend service that actually enforces it (named per
 * block); the SERVER decides, these only keep the copy truthful. Change
 * a rule server-side and this file is the one place the UI must follow.
 */
import type { TravelClass } from '../api/quotes';

/** Cancellation time-tiers - mirrors booking-service CancellationPolicy. */
export const CANCELLATION_POLICY = {
  /** Cancelling earlier than this many hours before departure refunds 100%. */
  fullRefundHours: 72,
  /** Between fullRefundHours and this: 50%. Inside it: no refund. */
  halfRefundHours: 24,
  /** Online cancellation closes this many hours before departure. */
  onlineCloseHours: 2,
  /** PREMIUM fares keep their refund up to this many hours before departure. */
  premiumRefundHours: 6,
  /** SAVER fares withhold this percentage as a fare-rule cancellation fee. */
  saverFeePercent: 30,
} as const;

/** Check-in and boarding cut-offs - mirror checkin-service windows. */
export const CHECKIN_POLICY = {
  /** Online check-in opens this many hours before departure. */
  opensHoursBeforeDeparture: 48,
  /** Online check-in (and the airport counter) closes this many minutes before departure. */
  closesMinutesBeforeDeparture: 60,
  /** The boarding gate closes this many minutes before departure. */
  gateClosesMinutesBeforeDeparture: 20,
  /** Boarding passes show boarding at departure minus this many minutes. */
  boardingMinutesBeforeDeparture: 40,
  /** "Be at the gate by" advisory: this many minutes before boarding. */
  gateAdvisoryMinutesBeforeBoarding: 30,
} as const;

/** E-ticket validity: NVA (not valid after) = departure + this many days. */
export const TICKET_VALIDITY_DAYS = 120;

/**
 * Check-in close is CARRIER-dependent: Emirates closes 90 minutes before
 * departure; the SkyBook default is CHECKIN_POLICY.closesMinutesBeforeDeparture.
 */
export function checkinCloseMinutesFor(flightNumber?: string): number {
  return flightNumber?.startsWith('EK') ? 90 : CHECKIN_POLICY.closesMinutesBeforeDeparture;
}

/**
 * Weight-concept carriers (Emirates) sell extra baggage as kilos - each
 * purchased unit is a 5 kg step; piece-concept carriers count bags.
 */
export function usesWeightConceptBaggage(flightNumber?: string): boolean {
  return flightNumber?.startsWith('EK') ?? false;
}

/**
 * Free baggage allowance per cabin, as printed on ticket documents -
 * mirrors notification-service TicketPdfTemplate.baggageFor().
 */
export const BAGGAGE_ALLOWANCES: Record<TravelClass, string> = {
  ECONOMY: '25 kg checked + 7 kg cabin',
  PREMIUM_ECONOMY: '30 kg checked + 7 kg cabin',
  BUSINESS: '40 kg checked + 2 cabin pieces (7 kg each)',
  FIRST: '50 kg checked + 2 cabin pieces (7 kg each)',
} as const;

/** Fallback allowance when a passenger row carries no cabin. */
export const DEFAULT_BAGGAGE_ALLOWANCE = BAGGAGE_ALLOWANCES.ECONOMY;

/**
 * Booking class (RBD) by cabin + fare brand - mirrors the emailed PDF's
 * mapping. A real system takes this from airline/GDS inventory; until
 * SkyBook has a class feed, the letter at least stays inside the right
 * cabin's range (First is F/A, never Economy Saver's S).
 */
export function bookingClassFor(travelClass?: string, fareType?: string): string {
  const cls = travelClass ?? 'ECONOMY';
  const fare = fareType ?? 'FLEXI';
  switch (cls) {
    case 'FIRST': return fare === 'PREMIUM' ? 'A' : 'F';
    case 'BUSINESS': return fare === 'PREMIUM' ? 'C' : 'J';
    case 'PREMIUM_ECONOMY': return fare === 'PREMIUM' ? 'E' : 'W';
    default:
      return fare === 'SAVER' ? 'S' : fare === 'PREMIUM' ? 'Y' : 'B';
  }
}
