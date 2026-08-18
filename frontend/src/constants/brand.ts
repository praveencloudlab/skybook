/**
 * Brand identity - the single source of truth for every customer-facing
 * name, slogan and contact detail. Documents (e-ticket, boarding pass),
 * screens and emails all read from here so a rebrand is a one-file edit.
 *
 * NOTE: the emailed PDF (notification-service TicketPdfTemplate.java) is
 * this document's twin - any change here must be mirrored there.
 */

/** Names and slogans printed on documents and screens. */
export const BRAND = {
  name: 'SkyBook',
  displayName: 'SkyBook',
  legalName: 'SkyBook Ltd',
  tagline: 'Electronic Ticket & Itinerary Receipt',
  alliance: 'PARTNER NETWORK',
  /** IATA-style issuing office line on the e-ticket. */
  printingOffice: 'SKYBOOK DIGITAL, DIGITAL OFFICE',
} as const;

/**
 * Carrier contact block (fictional airline - Ofcom drama-range phone
 * number and an invented registered office; nothing here is real).
 */
export const CARRIER_CONTACT = {
  phone: '+44 20 7946 0958',
  email: 'support@flyskybook.com',
  registeredOffice:
    'SkyBook Ltd, One Skyway House, 100 Aviation Way, London EC2X 9SB, United Kingdom',
  companyNumber: '03481976',
} as const;

/** Customer-facing URLs printed on documents (display text, not links). */
export const BRAND_LINKS = {
  baggage: 'flyskybook.com/baggage',
  conditions: 'flyskybook.com/conditions',
} as const;
