import { useEffect, useState } from 'react';
import { profileApi, type Profile, type SavedTraveller } from '../../api/profile';
import { emptyPassenger, type PassengerDraft } from './PassengerForm';

/**
 * "Who is travelling?" (FRONTEND_MODULE.md Module 14 → booking prefill).
 *
 * <p>The whole point of the profile and saved travellers: at booking time you
 * pick yourself or a saved companion and the passenger form fills in - no
 * retyping a passport for every trip. Falls back silently to manual entry if the
 * profile can't be loaded, and offers "Someone else" to clear back to a blank
 * form.
 */
function splitName(fullName: string | null): { firstName: string; lastName: string } {
  const parts = (fullName ?? '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return { firstName: '', lastName: '' };
  if (parts.length === 1) return { firstName: parts[0], lastName: '' };
  return { firstName: parts[0], lastName: parts.slice(1).join(' ') };
}

function profileToDraft(profile: Profile): PassengerDraft {
  const { firstName, lastName } = splitName(profile.fullName);
  return {
    ...emptyPassenger(),
    firstName,
    lastName,
    dob: profile.dateOfBirth ?? '',
    nationality: profile.nationality ?? 'GBR',
    passportNumber: profile.passportNumber ?? '',
    passportExpiry: profile.passportExpiry ?? '',
    email: profile.email ?? '',
  };
}

function travellerToDraft(t: SavedTraveller): PassengerDraft {
  return {
    ...emptyPassenger(),
    title: t.title ?? 'Mr',
    firstName: t.firstName,
    lastName: t.lastName,
    dob: t.dateOfBirth ?? '',
    nationality: t.nationality ?? 'GBR',
    passportNumber: t.passportNumber ?? '',
    passportExpiry: t.passportExpiry ?? '',
  };
}

type Choice = 'me' | 'manual' | number; // number = saved-traveller id

export function BookForPicker({
  onSelect,
}: {
  /** Fills the passenger form; email is offered only for "Myself". */
  onSelect: (draft: PassengerDraft, contactEmail?: string) => void;
}) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [travellers, setTravellers] = useState<SavedTraveller[]>([]);
  const [choice, setChoice] = useState<Choice>('manual');

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      profileApi.get(controller.signal).catch(() => null),
      profileApi.travellers(controller.signal).catch(() => []),
    ]).then(([p, list]) => {
      setProfile(p);
      setTravellers(list);
      // Default to "Myself" when there's anything worth prefilling.
      if (p && (p.fullName || p.passportNumber)) {
        setChoice('me');
        onSelect(profileToDraft(p), p.email);
      }
    });
    return () => controller.abort();
    // Run once; onSelect is stable enough for this one-shot prefill.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Nothing to offer (no profile data and no travellers) - stay out of the way.
  if (!profile?.fullName && !profile?.passportNumber && travellers.length === 0) {
    return null;
  }

  function pick(next: Choice) {
    setChoice(next);
    if (next === 'me' && profile) {
      onSelect(profileToDraft(profile), profile.email);
    } else if (next === 'manual') {
      onSelect(emptyPassenger());
    } else {
      const t = travellers.find((x) => x.id === next);
      if (t) onSelect(travellerToDraft(t));
    }
  }

  const options: Array<{ id: Choice; label: string }> = [
    ...(profile?.fullName || profile?.passportNumber ? [{ id: 'me' as Choice, label: 'Myself' }] : []),
    ...travellers.map((t) => ({ id: t.id as Choice, label: `${t.firstName} ${t.lastName}` })),
    { id: 'manual', label: 'Someone else' },
  ];

  return (
    <div className="mb-4">
      <p className="mb-2 text-sm font-medium text-slate-700">Who is travelling?</p>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => {
          const active = choice === option.id;
          return (
            <button
              key={String(option.id)}
              type="button"
              onClick={() => pick(option.id)}
              aria-pressed={active}
              className={
                'rounded-full px-3.5 py-1.5 text-sm font-medium transition ' +
                (active
                  ? 'bg-brand-600 text-white shadow-[var(--shadow-btn)]'
                  : 'border border-slate-200 bg-white text-slate-600 hover:border-brand-300 hover:text-brand-700')
              }
            >
              {option.label}
            </button>
          );
        })}
      </div>
      <p className="mt-1.5 text-xs text-slate-400">
        Prefilled from your profile — check the details are current before you pay.
      </p>
    </div>
  );
}
