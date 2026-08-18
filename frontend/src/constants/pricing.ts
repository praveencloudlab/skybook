/**
 * Ancillary pricing shown before the server quotes - each figure mirrors
 * the backend policy that actually charges it. Taxes live separately in
 * lib/taxes.ts (they need per-airport computation, not just a figure).
 */

/** Flat price per extra checked bag - mirrors booking-service's EXTRA_BAG_FEE. */
export const EXTRA_BAG_FEE = 40;
