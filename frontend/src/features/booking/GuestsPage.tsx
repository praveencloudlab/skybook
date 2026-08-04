import { t } from '../../lib/i18n';
import { useEffect, useState, type FormEvent } from 'react';
import { profileApi, type SavedTraveller } from '../../api/profile';
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
  contactPhone,
  onContactPhoneChange,
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
  /** Optional contact phone - lands on the booking's contact card. */
  contactPhone: string;
  onContactPhoneChange: (phone: string) => void;
  total: number;
  onBack: () => void;
  onContinue: () => void;
}) {
  const { subject, signedIn } = useSession();
  const [errors, setErrors] = useState<Record<string, string>[]>(() => paxTypes.map(() => ({})));
  const [contactError, setContactError] = useState<string | undefined>();
  const [phoneError, setPhoneError] = useState<string | undefined>();

  // Saved travellers (passenger features): one click fills the next empty
  // guest form from the profile's address book - no retyping documents for
  // the family. Loaded only for a signed-in booker; failure just hides chips.
  const [saved, setSaved] = useState<SavedTraveller[]>([]);
  useEffect(() => {
    if (!signedIn) return;
    const controller = new AbortController();
    profileApi.travellers(controller.signal).then(setSaved).catch(() => {});
    return () => controller.abort();
  }, [signedIn]);

  function update(index: number, draft: PassengerDraft) {
    onGuestsChange(guests.map((g, i) => (i === index ? draft : g)));
  }

  function fillFromSaved(traveller: SavedTraveller) {
    // Fill the first guest form still missing a name; if all are named,
    // overwrite the last one (the user is correcting a pick).
    const target = guests.findIndex((g) => !g.firstName.trim());
    const index = target >= 0 ? target : guests.length - 1;
    update(index, {
      title: traveller.title ?? guests[index].title,
      firstName: traveller.firstName,
      lastName: traveller.lastName,
      dob: traveller.dateOfBirth ?? '',
      nationality: traveller.nationality ?? '',
      passportNumber: traveller.passportNumber ?? '',
      passportExpiry: traveller.passportExpiry ?? '',
    });
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
    // The category MUST be passed: the form bounds a child's date-of-birth
    // field to its own age band, so validating that same date against the
    // default adult band rejects every correctly-entered child and infant.
    const perPax = guests.map((g, i) => validatePassenger(g, paxTypes[i]));
    const email = contactEmail.trim() || subject || '';
    const emailError = email ? undefined : 'A contact email is required';
    // Required the way airlines require it: disruption messaging - delays,
    // gate changes, cancellations - reaches the passenger by phone. Loose
    // international shape here; the server enforces its own on the DTO.
    const phone = contactPhone.trim();
    const phoneErr = !phone
      ? 'A contact phone is required'
      : /^\+?[0-9][0-9 ()-]{5,18}[0-9]$/.test(phone)
        ? undefined
        : 'Enter a valid phone number';
    setErrors(perPax);
    setContactError(emailError);
    setPhoneError(phoneErr);
    if (perPax.some((e) => Object.keys(e).length > 0) || emailError || phoneErr) {
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

          {saved.length > 0 ? (
            <div className="mt-4 rounded-xl border border-slate-200 bg-slate-50/60 px-4 py-3">
              <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">
                Fill from saved travellers
              </div>
              <div className="mt-2 flex flex-wrap gap-2">
                {saved.map((t) => (
                  <button key={t.id} type="button" onClick={() => fillFromSaved(t)}
                    className="inline-flex items-center gap-1.5 rounded-full border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 transition hover:border-accent-500 hover:text-accent-600">
                    <span className="grid h-5 w-5 place-items-center rounded-full bg-brand-600 text-[9px] font-bold text-white">
                      {(t.firstName[0] ?? '') + (t.lastName[0] ?? '')}
                    </span>
                    {t.firstName} {t.lastName}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

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
            <div className="mt-3 grid max-w-xl gap-3 sm:grid-cols-2">
              <Field
                label="Email address"
                type="email"
                value={contactEmail}
                onChange={(e) => onContactEmailChange(e.target.value)}
                error={contactError}
                placeholder={subject ?? 'name@example.com'}
                autoComplete="email"
              />
              <Field
                label="Phone"
                type="tel"
                value={contactPhone}
                onChange={(e) => onContactPhoneChange(e.target.value)}
                error={phoneError}
                placeholder="+44 7700 900123"
                autoComplete="tel"
              />
            </div>
          </section>

          <div className="mt-7 flex items-center justify-between">
            <button
              type="button"
              onClick={onBack}
              className="rounded-full border border-slate-300 bg-white px-6 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              {t('stepper.back')}
            </button>
            <Button type="submit">{t('cta.continue')}</Button>
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
