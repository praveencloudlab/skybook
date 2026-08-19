import { NATIONALITIES, type PassengerDetail, type PassengerType } from '../../api/bookings';
import type { FareType, TravelClass } from '../../api/quotes';
import { Field } from '../../components/Field';

/** ISO date exactly n years before today - the age-band boundaries. */
function yearsAgoIso(years: number): string {
  const date = new Date();
  date.setFullYear(date.getFullYear() - years);
  return date.toISOString().slice(0, 10);
}

/**
 * Date-of-birth bounds per declared traveller type. The server derives the
 * category from the DOB (PassengerCategory: under 2 infant, under 12 child),
 * so the form constrains the input to the band the traveller was declared as -
 * catching "adult with a 2019 birthday" here, not as a surprise later.
 */
function dobBounds(category: PassengerType): { min: string; max: string; hint: string } {
  switch (category) {
    case 'INFANT':
      return { min: yearsAgoIso(2), max: new Date().toISOString().slice(0, 10), hint: 'Under 2 years old' };
    case 'CHILD':
      return { min: yearsAgoIso(12), max: yearsAgoIso(2), hint: '2 to 11 years old' };
    default:
      return { min: '1900-01-01', max: yearsAgoIso(12), hint: '12 years or older' };
  }
}

/**
 * Passenger details (FRONTEND_MODULE.md §5 screen 5).
 *
 * <p>Validation mirrors the server's contract exactly - nationality is ISO-3166
 * alpha-3 (the server enforces exactly three characters), passport number and
 * expiry are required, date of birth is required. Getting a 400 back for a
 * two-letter country code after filling in a whole form is a poor way to learn
 * the rule, so nationality is a select rather than free text.
 */
export interface PassengerDraft {
  title: string;
  firstName: string;
  lastName: string;
  dob: string;
  nationality: string;
  passportNumber: string;
  passportExpiry: string;
  /** Per-passenger email - mandatory, so disruption notices reach every traveller. */
  email: string;
}

export function emptyPassenger(): PassengerDraft {
  return {
    title: 'Mr',
    firstName: '',
    lastName: '',
    dob: '',
    nationality: 'GBR',
    passportNumber: '',
    passportExpiry: '',
    email: '',
  };
}

export function toPassengerDetail(
  draft: PassengerDraft,
  cabin: TravelClass,
  fare: FareType,
  seatNumber: string | null,
  extraBags = 0,
  returnSeatNumber: string | null = null,
  connectionSeatNumbers: (string | null)[] = [],
  returnExtraBags = 0,
): PassengerDetail {
  return {
    title: draft.title,
    firstName: draft.firstName.trim(),
    lastName: draft.lastName.trim(),
    dob: draft.dob,
    nationality: draft.nationality,
    passportNumber: draft.passportNumber.trim(),
    passportExpiry: draft.passportExpiry,
    email: draft.email.trim(),
    travelClass: cabin,
    fareType: fare,
    // Omitted entirely when auto-assigning: sending null would be a different
    // request than "you choose", and the server's free auto-assignment path is
    // keyed on the field being absent.
    ...(seatNumber ? { seatNumber } : {}),
    ...(returnSeatNumber ? { returnSeatNumber } : {}),
    // Index-aligned with connectionFlightIds: blanks keep later legs' picks
    // in the right position, so only send when at least one pick exists.
    ...(connectionSeatNumbers.some((s) => s)
      ? { connectionSeatNumbers: connectionSeatNumbers.map((s) => s ?? '') }
      : {}),
    ...(extraBags > 0 ? { extraBags } : {}),
    // Explicit even when 0: the server falls back to extraBags when this is
    // ABSENT, and "0 bags on the way home" is a real choice, not an absence.
    ...(returnExtraBags !== extraBags ? { returnExtraBags } : {}),
  };
}

/** Missing/invalid fields, keyed by field name - empty when the draft is valid. */
export function validatePassenger(
  draft: PassengerDraft,
  category: PassengerType = 'ADULT',
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!draft.firstName.trim()) errors.firstName = 'First name is required';
  if (!draft.lastName.trim()) errors.lastName = 'Last name is required';
  if (!draft.dob) {
    errors.dob = 'Date of birth is required';
  } else {
    const { min, max } = dobBounds(category);
    if (draft.dob < min || draft.dob > max) {
      errors.dob =
        category === 'ADULT'
          ? 'An adult must be 12 or older'
          : category === 'CHILD'
            ? 'A child is 2 to 11 years old'
            : 'An infant is under 2 years old';
    }
  }
  if (!draft.passportNumber.trim()) errors.passportNumber = 'Passport number is required';
  if (!draft.passportExpiry) {
    errors.passportExpiry = 'Passport expiry is required';
  } else if (new Date(draft.passportExpiry) <= new Date()) {
    // Caught here rather than at the airport.
    errors.passportExpiry = 'Passport has expired';
  }
  // Mandatory per traveller, mirroring the server's @NotBlank @Email: the
  // contact email covers the booking, but disruption notices are per person.
  const email = draft.email.trim();
  if (!email) {
    errors.email = 'Email is required';
  } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.email = 'Enter a valid email address';
  }
  return errors;
}

export function PassengerForm({
  draft,
  category = 'ADULT',
  errors,
  onChange,
}: {
  draft: PassengerDraft;
  /** Declared traveller type - bounds the date-of-birth band. */
  category?: PassengerType;
  errors: Record<string, string>;
  onChange: (draft: PassengerDraft) => void;
}) {
  const set = (patch: Partial<PassengerDraft>) => onChange({ ...draft, ...patch });
  const dob = dobBounds(category);

  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <div className="space-y-1.5">
        <label htmlFor="title" className="block text-sm font-medium text-slate-700">
          Title
        </label>
        <select
          id="title"
          value={draft.title}
          onChange={(e) => set({ title: e.target.value })}
          className="w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
        >
          {['Mr', 'Ms', 'Mrs', 'Dr'].map((title) => (
            <option key={title}>{title}</option>
          ))}
        </select>
      </div>

      <div className="hidden sm:block" />

      <Field
        label="First name"
        value={draft.firstName}
        onChange={(e) => set({ firstName: e.target.value })}
        error={errors.firstName}
        autoComplete="given-name"
      />
      <Field
        label="Last name"
        value={draft.lastName}
        onChange={(e) => set({ lastName: e.target.value })}
        error={errors.lastName}
        autoComplete="family-name"
      />

      <Field
        label="Date of birth"
        type="date"
        value={draft.dob}
        onChange={(e) => set({ dob: e.target.value })}
        error={errors.dob}
        hint={dob.hint}
        // Bounded to the declared traveller type's age band - the server
        // derives adult/child/infant from this date, so a mismatch here would
        // only surface as a confusing failure later.
        min={dob.min}
        max={dob.max}
      />

      <div className="space-y-1.5">
        <label htmlFor="nationality" className="block text-sm font-medium text-slate-700">
          Nationality
        </label>
        <select
          id="nationality"
          value={draft.nationality}
          onChange={(e) => set({ nationality: e.target.value })}
          className="w-full rounded-xl border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
        >
          {NATIONALITIES.map((code) => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
        {/* A select, not free text: the server wants ISO-3166 alpha-3 and
            rejects anything else - which is not obvious from a blank box. */}
        <p className="text-xs text-slate-500">Three-letter country code</p>
      </div>

      <Field
        label="Passport number"
        value={draft.passportNumber}
        onChange={(e) => set({ passportNumber: e.target.value.toUpperCase() })}
        error={errors.passportNumber}
        maxLength={20}
      />
      <Field
        label="Passport expiry"
        type="date"
        value={draft.passportExpiry}
        onChange={(e) => set({ passportExpiry: e.target.value })}
        error={errors.passportExpiry}
        min={new Date().toISOString().slice(0, 10)}
      />

      <Field
        label="Passenger email"
        type="email"
        value={draft.email}
        onChange={(e) => set({ email: e.target.value })}
        error={errors.email}
        autoComplete="email"
        hint="For this traveller's notices - delays, gate changes, boarding passes"
      />
    </div>
  );
}
