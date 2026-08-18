/**
 * Printable-document palette - the ticket-office ledger look shared by
 * the downloadable e-ticket and the emailed PDF (TicketPdfTemplate.java
 * MAROON/INK/LABEL must stay identical). App screens use Tailwind;
 * these exist because printable HTML is inline-styled by design.
 */

/** E-ticket (Style C ledger) colors. */
export const TICKET_THEME = {
  /** Header bands, PNR, ledger total rule. */
  maroon: '#5a1836',
  /** Body text. */
  ink: '#1a1a1a',
  /** Small-caps field labels and rule headings. */
  label: '#8a93a3',
} as const;

/** Boarding pass accent colors. */
export const BOARDING_PASS_THEME = {
  red: '#e11b22',
  stripBlue: '#cfe0f5',
} as const;
