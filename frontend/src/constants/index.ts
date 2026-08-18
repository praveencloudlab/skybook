/**
 * UI constants - one import site for brand identity, passenger-facing
 * policy figures, ancillary pricing and document palettes:
 *
 *   import { BRAND, CANCELLATION_POLICY, EXTRA_BAG_FEE } from '../constants';
 *
 * Related single-purpose modules that intentionally live elsewhere:
 * tax rates + computation in lib/taxes.ts, airline names in
 * api/flights.ts (AIRLINE_NAMES), cabin labels in api/quotes.ts
 * (TRAVEL_CLASS_LABELS), airport directory in api/airports.ts.
 */
export { BRAND, CARRIER_CONTACT, BRAND_LINKS } from './brand';
export {
  CANCELLATION_POLICY,
  CHECKIN_POLICY,
  TICKET_VALIDITY_DAYS,
  BAGGAGE_ALLOWANCES,
  DEFAULT_BAGGAGE_ALLOWANCE,
  bookingClassFor,
  checkinCloseMinutesFor,
  usesWeightConceptBaggage,
} from './policy';
export { EXTRA_BAG_FEE } from './pricing';
export { TICKET_THEME, BOARDING_PASS_THEME } from './theme';
