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

function linesFor(airport: string, cabin: string): TaxLine[] {
  const premiumCabin = cabin !== 'ECONOMY';
  if (airport === 'LHR') {
    return [
      { code: 'GB', label: TAX_LABELS.GB, amount: premiumCabin ? 216 : 90 },
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

/** Merged tax lines for a journey: every departure x every passenger. */
export function computeTaxes(departureAirports: string[], cabin: string, paxCount: number): TaxLine[] {
  const merged = new Map<string, TaxLine>();
  for (const airport of departureAirports) {
    for (const line of linesFor(airport.toUpperCase(), cabin)) {
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
