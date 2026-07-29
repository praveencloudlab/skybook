import { useState, type FormEvent } from 'react';
import type { Flight } from '../../api/flights';
import type { PassengerType } from '../../api/bookings';
import type { FareType, TravelClass } from '../../api/quotes';
import type { Travellers } from '../../components/TravellersPicker';
import { BookingStepper } from '../../components/BookingStepper';
import { SummaryRail } from '../../components/SummaryRail';
import { Button } from '../../components/Button';
import { Field } from '../../components/Field';
import { useSession } from '../auth/useSession';
import { BookForPicker } from './BookForPicker';
import { PassengerForm, validatePassenger, type PassengerDraft } from './PassengerForm';

/**
 * Guest information (carrier flow step 2): one labelled section per declared
 * traveller - Adult 1, Adult 2, Child 1, Infant 1 - each DOB-bounded to its
 * category, with the booking contact at the end. Purely data entry: seats,
 * bags and payment are their own steps after this.
 */
export function GuestsPage({
  flight,
  cabin,
  fare,
  currency,
  travellers,
  paxTypes,
  guests,
  onGuestsChange,
  contactEmail,
  onContactEmailChange,
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
  onGuestsChange: (guests: PassengerDraft[]) => void;
  contactEmail: string;
  onContactEmailChange: (email: string) => void;
  total: number;
  onBack: () => void;
  onContinue: () => void;
}) {
  const { subject } = useSession();
  const [errors, setErrors] = useState<Record<string, string>[]>(() => paxTypes.map(() => ({})));
  const [contactError, setContactError] = useState<string | undefined>();

  function update(index: number, draft: PassengerDraft) {
    onGuestsChange(guests.map((g, i) => (i === index ? draft : g)));
  }

  // "Adult 1" / "Child 1" / "Infant 1" numbering within each category.
  function sectionLabel(index: number): string {
    const type = paxTypes[index];
    const nth = paxTypes.slice(0, index + 1).filter((t) => t === type).length;
    const name = type === 'ADULT' ? 'Adult' : type === 'CHILD' ? 'Child' : 'Infant';
    const count = paxTypes.filter((t) => t === type).length;
    return count > 1 ? `${name} ${nth}` : name;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const perPax = guests.map((g) => validatePassenger(g));
    const email = contactEmail.trim() || subject || '';
    const emailError = email ? undefined : 'A contact email is required';
    setErrors(perPax);
    setContactError(emailError);
    if (perPax.some((e) => Object.keys(e).length > 0) || emailError) {
      return;
    }
    if (!contactEmail.trim() && subject) {
      onContactEmailChange(subject);
    }
    onContinue();
  }

  return (
    <>
      <BookingStepper
        current="guests"
        flight={flight}
        route={`${flight.originAirportCode} → ${flight.destinationAirportCode}`}
        onModify={onBack}
      />

      <main className="mx-auto grid max-w-6xl gap-6 px-4 py-6 sm:px-6 lg:grid-cols-[1fr_320px]">
        <form onSubmit={submit} noValidate className="rounded-2xl bg-white p-5 shadow-[var(--shadow-card)] sm:p-7">
          <div className="flex items-start gap-2.5 rounded-xl bg-brand-50 px-4 py-3 text-sm text-slate-700">
            <svg viewBox="0 0 24 24" className="mt-0.5 h-4 w-4 shrink-0 fill-brand-600" aria-hidden="true">
              <path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z" />
            </svg>
            For immigration and security reasons, enter names exactly as they appear on the passport.
          </div>

          <h1 className="mt-5 text-2xl font-bold tracking-tight text-slate-900">Guest information</h1>

          {guests.map((draft, index) => (
            <section key={index} className="mt-6 border-t border-slate-100 pt-5 first:border-0">
              <h2 className="text-base font-bold text-slate-900">{sectionLabel(index)}</h2>
              {paxTypes[index] === 'INFANT' && index === paxTypes.indexOf('INFANT') ? (
                <p className="mt-1 text-xs leading-relaxed text-slate-500">
                  Infants up to two years old travel on an adult's lap. Flying with more than one
                  infant? Each must be accompanied by an adult.
                </p>
              ) : null}
              <div className="mt-3 space-y-3">
                <BookForPicker
                  onSelect={(picked, email) => {
                    update(index, picked);
                    if (index === 0 && email) onContactEmailChange(email);
                  }}
                />
                <PassengerForm
                  draft={draft}
                  errors={errors[index] ?? {}}
                  onChange={(d) => update(index, d)}
                  category={paxTypes[index]}
                />
              </div>
            </section>
          ))}

          <section className="mt-6 border-t border-slate-100 pt-5">
            <h2 className="text-base font-bold text-slate-900">Contact information</h2>
            <p className="mt-1 text-xs text-slate-500">
              The booking confirmation and boarding passes go here.
            </p>
            <div className="mt-3 max-w-sm">
              <Field
                label="Email address"
                type="email"
                value={contactEmail}
                onChange={(e) => onContactEmailChange(e.target.value)}
                error={contactError}
                placeholder={subject ?? 'name@example.com'}
                autoComplete="email"
              />
            </div>
          </section>

          <div className="mt-7 flex items-center justify-between">
            <button
              type="button"
              onClick={onBack}
              className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              Back
            </button>
            <Button type="submit">Continue</Button>
          </div>
        </form>

        <SummaryRail
          flight={flight}
          cabin={cabin}
          fare={fare}
          currency={currency}
          travellers={travellers}
          guestNames={guests.map((g) => `${g.title} ${g.firstName} ${g.lastName}`.trim())}
          total={total}
        />
      </main>
    </>
  );
}
