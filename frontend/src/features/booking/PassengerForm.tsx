import { NATIONALITIES, type PassengerDetail } from '../../api/bookings';
import type { FareType, TravelClass } from '../../api/quotes';
import { Field } from '../../components/Field';

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
  };
}

export function toPassengerDetail(
  draft: PassengerDraft,
  cabin: TravelClass,
  fare: FareType,
  seatNumber: string | null,
): PassengerDetail {
  return {
    title: draft.title,
    firstName: draft.firstName.trim(),
    lastName: draft.lastName.trim(),
    dob: draft.dob,
    nationality: draft.nationality,
    passportNumber: draft.passportNumber.trim(),
    passportExpiry: draft.passportExpiry,
    travelClass: cabin,
    fareType: fare,
    // Omitted entirely when auto-assigning: sending null would be a different
    // request than "you choose", and the server's free auto-assignment path is
    // keyed on the field being absent.
    ...(seatNumber ? { seatNumber } : {}),
  };
}

/** Missing/invalid fields, keyed by field name - empty when the draft is valid. */
export function validatePassenger(draft: PassengerDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!draft.firstName.trim()) errors.firstName = 'First name is required';
  if (!draft.lastName.trim()) errors.lastName = 'Last name is required';
  if (!draft.dob) errors.dob = 'Date of birth is required';
  if (!draft.passportNumber.trim()) errors.passportNumber = 'Passport number is required';
  if (!draft.passportExpiry) {
    errors.passportExpiry = 'Passport expiry is required';
  } else if (new Date(draft.passportExpiry) <= new Date()) {
    // Caught here rather than at the airport.
    errors.passportExpiry = 'Passport has expired';
  }
  return errors;
}

export function PassengerForm({
  draft,
  errors,
  onChange,
}: {
  draft: PassengerDraft;
  errors: Record<string, string>;
  onChange: (draft: PassengerDraft) => void;
}) {
  const set = (patch: Partial<PassengerDraft>) => onChange({ ...draft, ...patch });

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
          className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
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
        // Nobody on this aircraft was born in 1723; a sane bound stops a typo
        // becoming a server-side validation error.
        min="1900-01-01"
        max={new Date().toISOString().slice(0, 10)}
      />

      <div className="space-y-1.5">
        <label htmlFor="nationality" className="block text-sm font-medium text-slate-700">
          Nationality
        </label>
        <select
          id="nationality"
          value={draft.nationality}
          onChange={(e) => set({ nationality: e.target.value })}
          className="w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-2 focus:ring-brand-500/30"
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
    </div>
  );
}
