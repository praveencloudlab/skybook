import { t } from '../../lib/i18n';
import type { Flight } from '../../api/flights';
import type { PassengerType } from '../../api/bookings';
import { FARE_TYPE_LABELS, type FareType, type TravelClass } from '../../api/quotes';
import type { Travellers } from '../../components/TravellersPicker';
import { BookingStepper } from '../../components/BookingStepper';
import { SummaryRail, type SummaryExtra } from '../../components/SummaryRail';
import { Button } from '../../components/Button';
import { price } from '../../lib/format';
import type { PassengerDraft } from './PassengerForm';

import { EXTRA_BAG_FEE } from '../../constants';

/** Re-exported so existing imports keep working; the value lives in constants/pricing. */
export { EXTRA_BAG_FEE };

/** Included allowance by cabin - the same table the e-ticket prints. */
const INCLUDED_BAGGAGE: Record<TravelClass, { checked: string; cabin: string }> = {
  ECONOMY: { checked: '25kg Checked baggage', cabin: '7kg cabin baggage' },
  PREMIUM_ECONOMY: { checked: '30kg Checked baggage', cabin: '7kg cabin baggage' },
  BUSINESS: { checked: '40kg Checked baggage', cabin: '10kg cabin baggage' },
  FIRST: { checked: '50kg Checked baggage', cabin: '10kg cabin baggage' },
};

/**
 * Extra baggage (carrier flow step 4): every guest sees their fare's included
 * allowance and can add extra 23kg bags at a flat fee each. The fee is priced
 * server-side into the passenger's immutable fare breakdown - what this page
 * shows is exactly what the invoice will carry.
 */
export function BagsPage({
  flight,
  cabin,
  fare,
  currency,
  travellers,
  paxTypes,
  guests,
  bags,
  onAdjustBag,
  returnBags = null,
  onAdjustReturnBag,
  extras,
  total,
  onBack,
  onContinue,
}: {
  flight: Flight;
  cabin: TravelClass;
  fare: FareType;
  currency: string;
  travellers: Travellers;
  paxTypes: PassengerType[];
  guests: PassengerDraft[];
  /** Extra bags per passenger, aligned with guests order. */
  bags: number[];
  /** Functional adjust - rapid clicks must not read stale counts. */
  onAdjustBag: (index: number, delta: number) => void;
  /** Round trip only: the return direction's own bag counts + stepper. */
  returnBags?: number[] | null;
  onAdjustReturnBag?: (index: number, delta: number) => void;
  extras: SummaryExtra[];
  total: number;
  onBack: () => void;
  onContinue: () => void;
}) {
  const allowance = INCLUDED_BAGGAGE[cabin];



  return (
    <>
      <BookingStepper
        current="bags"
        flight={flight}
        route={`${flight.originAirportCode} → ${flight.destinationAirportCode}`}
        onModify={onBack}
      />

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[1fr_320px]">
        <div className="rounded-2xl bg-white p-5 shadow-[var(--shadow-card)] sm:p-7">
          <div className="flex items-start gap-2.5 rounded-xl bg-brand-50 px-4 py-3 text-sm text-slate-700">
            <svg viewBox="0 0 24 24" className="mt-0.5 h-4 w-4 shrink-0 fill-brand-600" aria-hidden="true">
              <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z" />
            </svg>
            Need to take extra bags? Add them now — each extra bag is a 23kg checked piece.
          </div>

          <h1 className="mt-5 text-2xl font-bold tracking-tight text-slate-900">Extra baggage</h1>

          <div className="mt-2 inline-flex rounded-full bg-brand-950 px-5 py-2 text-sm font-bold text-white">
            {flight.originAirportCode} – {flight.destinationAirportCode}
          </div>

          <div className="mt-5 space-y-4">
            {guests.map((guest, index) => {
              const infant = paxTypes[index] === 'INFANT';
              const name = `${guest.firstName} ${guest.lastName}`.trim() || `Guest ${index + 1}`;
              return (
                <section key={index} className="rounded-2xl border border-slate-200 p-4 sm:p-5">
                  <div className="flex items-center justify-between gap-3">
                    <h2 className="text-base font-bold text-slate-900">{name}</h2>
                    {infant ? <span className="text-xs font-semibold text-slate-400">N/A — lap infant</span> : null}
                  </div>

                  {!infant ? (
                    <div className="mt-3 grid gap-4 sm:grid-cols-2">
                      {/* Included allowance. */}
                      <div className="rounded-xl border-l-4 border-brand-500 bg-slate-50 p-4">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-bold text-slate-900">{FARE_TYPE_LABELS[fare]}</span>
                          <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-emerald-700">
                            Included
                          </span>
                        </div>
                        <ul className="mt-2 space-y-1 text-sm text-slate-600">
                          <li className="flex items-center gap-1.5">
                            <BagIcon /> {allowance.checked}
                          </li>
                          <li className="flex items-center gap-1.5">
                            <BagIcon /> {allowance.cabin}
                          </li>
                        </ul>
                      </div>

                      {/* Extra bags stepper - per DIRECTION on a round trip:
                          2 bags out and 0 back is a real purchase pattern. */}
                      <div className="rounded-xl border border-slate-200 p-4">
                        <div className="flex items-center justify-between">
                          <h3 className="text-sm font-bold text-slate-900">Extra baggage</h3>
                          <span className="tabular text-xs text-slate-500">
                            {price(EXTRA_BAG_FEE, currency)} / bag
                          </span>
                        </div>
                        <BagStepper
                          label={returnBags ? 'Outbound' : undefined}
                          name={name}
                          count={bags[index]}
                          currency={currency}
                          onAdjust={(delta) => onAdjustBag(index, delta)}
                        />
                        {returnBags && onAdjustReturnBag ? (
                          <BagStepper
                            label="Return"
                            name={name}
                            count={returnBags[index]}
                            currency={currency}
                            onAdjust={(delta) => onAdjustReturnBag(index, delta)}
                          />
                        ) : null}
                      </div>
                    </div>
                  ) : (
                    <p className="mt-2 text-sm text-slate-500">
                      Lap infants have no separate baggage allowance.
                    </p>
                  )}
                </section>
              );
            })}
          </div>

          <div className="mt-7 flex items-center justify-between">
            <button
              type="button"
              onClick={onBack}
              className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              {t('stepper.back')}
            </button>
            <Button onClick={onContinue}>{t('cta.continue')}</Button>
          </div>
        </div>

        <SummaryRail
          flight={flight}
          cabin={cabin}
          fare={fare}
          currency={currency}
          travellers={travellers}
          guestNames={guests.map((g) => `${g.title} ${g.firstName} ${g.lastName}`.trim())}
          extras={extras}
          total={total}
        />
      </main>
    </>
  );
}

function BagIcon() {
  return (
    <svg viewBox="0 0 24 24" className="h-3.5 w-3.5 shrink-0 fill-slate-400" aria-hidden="true">
      <path d="M9 6V4a3 3 0 0 1 6 0v2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h2zm2 0h2V4a1 1 0 0 0-2 0v2z" />
    </svg>
  );
}

/** One direction's stepper row: − count + with the running charge under it. */
function BagStepper({ label, name, count, currency, onAdjust }: {
  label?: string;
  name: string;
  count: number;
  currency: string;
  onAdjust: (delta: number) => void;
}) {
  return (
    <div className="mt-3 flex items-center justify-between gap-2">
      {label ? <span className="text-xs font-semibold text-slate-500">{label}</span> : <span />}
      <div className="flex items-center gap-2">
        <button type="button" aria-label={`Fewer ${label ?? ''} bags for ${name}`} disabled={count <= 0}
          onClick={() => onAdjust(-1)}
          className="grid h-9 w-9 place-items-center rounded-lg bg-slate-200 text-lg font-semibold text-slate-600 transition hover:bg-slate-300 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-300">
          −
        </button>
        <span className="tabular grid h-9 w-14 place-items-center rounded-lg border border-slate-700 bg-white text-sm font-bold text-slate-900">
          {count} bag{count === 1 ? '' : 's'}
        </span>
        <button type="button" aria-label={`More ${label ?? ''} bags for ${name}`} disabled={count >= 5}
          onClick={() => onAdjust(1)}
          className="grid h-9 w-9 place-items-center rounded-lg bg-brand-900 text-lg font-semibold text-white transition hover:bg-brand-800 disabled:cursor-not-allowed disabled:bg-slate-200 disabled:text-slate-400">
          +
        </button>
        {count > 0 ? (
          <span className="tabular w-16 text-right text-xs font-semibold text-slate-700">
            + {price(count * EXTRA_BAG_FEE, currency)}
          </span>
        ) : <span className="w-16" />}
      </div>
    </div>
  );
}
