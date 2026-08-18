/**
 * Client mirror of booking-service's TaxPolicy - the SAME rates, so the
 * checkout summary's total equals the server's booked total to the penny.
 * Taxes are per passenger, per departure airport; a through-ticket via DXB
 * genuinely pays the UAE facility charge.
 */
export interface TaxLine {
  code: string;
  label: string;
  amount: number;
}

const INDIA = ['HYD', 'BOM', 'DEL', 'BLR', 'MAA', 'CCU', 'VTZ'];

export const TAX_LABELS: Record<string, string> = {
  GB: 'UK Air Passenger Duty',
  UB: 'UK Passenger Service Charge',
  AE: 'UAE Passenger Facility Charge',
  IN: 'India UDF & K3',
  XT: 'Intl airport charges',
};

/** UK APD Band B rose with the 2026/27 fiscal year - selected by DEPARTURE date. */
const APD_2026_27 = '2026-04-01';

function linesFor(airport: string, cabin: string, departureDate?: string): TaxLine[] {
  const premiumCabin = cabin !== 'ECONOMY';
  if (airport === 'LHR') {
    const when = departureDate ?? new Date().toISOString().slice(0, 10);
    const newRate = when >= APD_2026_27;
    return [
      { code: 'GB', label: TAX_LABELS.GB, amount: premiumCabin ? (newRate ? 244 : 216) : (newRate ? 102 : 90) },
      { code: 'UB', label: TAX_LABELS.UB, amount: 29.1 },
    ];
  }
  if (airport === 'DXB') {
    return [{ code: 'AE', label: TAX_LABELS.AE, amount: 16.3 }];
  }
  if (INDIA.includes(airport)) {
    return [{ code: 'IN', label: TAX_LABELS.IN, amount: 13.6 }];
  }
  return [{ code: 'XT', label: TAX_LABELS.XT, amount: 11.2 }];
}

/** One departure of the journey: where it leaves from, and when (rate selection). */
export interface Departure {
  airport: string;
  /** ISO date (yyyy-mm-dd) or full ISO timestamp; undefined = today's rates. */
  date?: string;
}

/** Merged tax lines for a journey: every departure x every passenger. */
export function computeTaxes(departures: Array<string | Departure>, cabin: string, paxCount: number): TaxLine[] {
  const merged = new Map<string, TaxLine>();
  for (const dep of departures) {
    const airport = (typeof dep === 'string' ? dep : dep.airport).toUpperCase();
    const date = typeof dep === 'string' ? undefined : dep.date?.slice(0, 10);
    for (const line of linesFor(airport, cabin, date)) {
      const existing = merged.get(line.code);
      const amount = line.amount * paxCount;
      if (existing) {
        existing.amount += amount;
      } else {
        merged.set(line.code, { ...line, amount });
      }
    }
  }
  return [...merged.values()];
}
