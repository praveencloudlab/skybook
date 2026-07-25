import { useEffect, useState, type FormEvent } from 'react';
import { NATIONALITIES } from '../../api/bookings';
import { PASSWORD_RULES, passwordPolicyMet } from '../../api/auth';
import { profileApi, type Profile, type SavedTraveller } from '../../api/profile';
import { Alert, ErrorAlert } from '../../components/Alert';
import { Button } from '../../components/Button';
import { Field } from '../../components/Field';
import { ApiError } from '../../lib/errors';

/**
 * Profile (FRONTEND_MODULE.md Module 14).
 *
 * <p>Only the sections the backend actually stores are here - personal details,
 * travel documents, saved travellers, and password. Deliberately no fake
 * controls for things that have no backend yet (2FA, device sessions, saved
 * cards, notification prefs): a switch that does nothing is worse than its
 * absence. Saved travellers feed the booking form's "book for" picker.
 */
export function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    profileApi
      .get(controller.signal)
      .then(setProfile)
      .catch((cause) => {
        if (cause instanceof DOMException && cause.name === 'AbortError') return;
        setError(cause instanceof ApiError ? cause : null);
      });
    return () => controller.abort();
  }, []);

  return (
    <main className="mx-auto max-w-3xl px-6 py-8">
      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Your profile</h1>
      <p className="mt-1 text-sm text-slate-500">
        Details you keep on file — reused to fill in bookings so you don't retype them.
      </p>

      <div className="mt-6 space-y-6">
        <ErrorAlert error={error} />
        {profile ? (
          <>
            <PersonalAndDocuments profile={profile} onSaved={setProfile} />
            <SavedTravellers />
            <ChangePassword />
          </>
        ) : !error ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : null}
      </div>
    </main>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <section className="card p-5">
      <h2 className="text-base font-semibold text-slate-900">{title}</h2>
      {subtitle ? <p className="mt-0.5 text-sm text-slate-500">{subtitle}</p> : null}
      <div className="mt-4">{children}</div>
    </section>
  );
}

function NationalitySelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <label className="space-y-1.5 text-sm">
      <span className="block font-medium text-slate-700">Nationality</span>
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-xl border border-slate-200 bg-slate-50/60 px-3.5 py-2.5 text-sm outline-none transition focus:border-brand-500 focus:bg-white focus:ring-4 focus:ring-brand-500/15"
      >
        <option value="">Select…</option>
        {NATIONALITIES.map((code) => (
          <option key={code} value={code}>
            {code}
          </option>
        ))}
      </select>
    </label>
  );
}

function PersonalAndDocuments({ profile, onSaved }: { profile: Profile; onSaved: (p: Profile) => void }) {
  const [form, setForm] = useState({
    fullName: profile.fullName ?? '',
    phone: profile.phone ?? '',
    dateOfBirth: profile.dateOfBirth ?? '',
    nationality: profile.nationality ?? '',
    passportNumber: profile.passportNumber ?? '',
    passportExpiry: profile.passportExpiry ?? '',
    emergencyContactName: profile.emergencyContactName ?? '',
    emergencyContactPhone: profile.emergencyContactPhone ?? '',
  });
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
    setSaved(false);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const updated = await profileApi.update({
        fullName: form.fullName,
        phone: form.phone,
        dateOfBirth: form.dateOfBirth || null,
        nationality: form.nationality || undefined,
        passportNumber: form.passportNumber,
        passportExpiry: form.passportExpiry || null,
        emergencyContactName: form.emergencyContactName,
        emergencyContactPhone: form.emergencyContactPhone,
      });
      onSaved(updated);
      setSaved(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit}>
      <Section title="Personal information" subtitle="Used to fill in your bookings.">
        <ErrorAlert error={error} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Full name" value={form.fullName} onChange={(e) => set('fullName', e.target.value)} autoComplete="name" />
          <Field label="Email" value={profile.email} disabled hint="Your sign-in email can't be changed here." />
          <Field label="Phone" value={form.phone} onChange={(e) => set('phone', e.target.value)} autoComplete="tel" />
          <Field label="Date of birth" type="date" value={form.dateOfBirth} onChange={(e) => set('dateOfBirth', e.target.value)} />
          <NationalitySelect value={form.nationality} onChange={(v) => set('nationality', v)} />
        </div>
      </Section>

      <div className="mt-6" />
      <Section title="Travel documents" subtitle="Your passport, so check-in and booking prefill it automatically.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Passport number" value={form.passportNumber} onChange={(e) => set('passportNumber', e.target.value)} />
          <Field label="Passport expiry" type="date" value={form.passportExpiry} onChange={(e) => set('passportExpiry', e.target.value)} />
        </div>
      </Section>

      <div className="mt-6" />
      <Section title="Emergency contact" subtitle="Who we'd reach in an emergency.">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Contact name" value={form.emergencyContactName} onChange={(e) => set('emergencyContactName', e.target.value)} />
          <Field label="Contact phone" value={form.emergencyContactPhone} onChange={(e) => set('emergencyContactPhone', e.target.value)} />
        </div>
        <div className="mt-4 flex items-center gap-3">
          <Button type="submit" busy={busy}>Save changes</Button>
          {saved ? <span className="text-sm text-emerald-700">Saved ✓</span> : null}
        </div>
      </Section>
    </form>
  );
}

function SavedTravellers() {
  const [list, setList] = useState<SavedTraveller[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [adding, setAdding] = useState(false);
  const [draft, setDraft] = useState({ firstName: '', lastName: '', dateOfBirth: '', nationality: '', passportNumber: '', passportExpiry: '' });
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    profileApi.travellers(controller.signal).then(setList).catch(() => setList([]));
    return () => controller.abort();
  }, []);

  async function add(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const created = await profileApi.addTraveller({
        firstName: draft.firstName,
        lastName: draft.lastName,
        dateOfBirth: draft.dateOfBirth || null,
        nationality: draft.nationality || undefined,
        passportNumber: draft.passportNumber,
        passportExpiry: draft.passportExpiry || null,
      });
      setList((l) => [...(l ?? []), created]);
      setDraft({ firstName: '', lastName: '', dateOfBirth: '', nationality: '', passportNumber: '', passportExpiry: '' });
      setAdding(false);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    setList((l) => (l ?? []).filter((t) => t.id !== id));
    try {
      await profileApi.deleteTraveller(id);
    } catch {
      // Reload on failure so the UI doesn't drift from the server.
      profileApi.travellers().then(setList).catch(() => {});
    }
  }

  return (
    <Section title="Saved travellers" subtitle="People you book for — pick them at booking time instead of retyping.">
      <ErrorAlert error={error} />
      <ul className="space-y-2">
        {list?.map((t) => (
          <li key={t.id} className="flex items-center justify-between gap-3 rounded-xl border border-slate-200 px-3.5 py-2.5">
            <div className="flex items-center gap-3">
              <span className="grid h-9 w-9 place-items-center rounded-full bg-brand-50 text-sm font-semibold text-brand-700">
                {(t.firstName[0] ?? '?').toUpperCase()}
              </span>
              <div>
                <p className="text-sm font-medium text-slate-900">{t.firstName} {t.lastName}</p>
                <p className="text-xs text-slate-500">
                  {t.passportNumber ? `Passport ${t.passportNumber}` : 'No passport on file'}
                  {t.nationality ? ` · ${t.nationality}` : ''}
                </p>
              </div>
            </div>
            <button type="button" onClick={() => remove(t.id)} className="text-sm font-medium text-slate-400 transition hover:text-red-600">
              Remove
            </button>
          </li>
        ))}
        {list?.length === 0 && !adding ? <li className="text-sm text-slate-500">No saved travellers yet.</li> : null}
      </ul>

      {adding ? (
        <form onSubmit={add} className="mt-3 rounded-xl border border-slate-200 bg-slate-50/60 p-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="First name" value={draft.firstName} onChange={(e) => setDraft({ ...draft, firstName: e.target.value })} required />
            <Field label="Last name" value={draft.lastName} onChange={(e) => setDraft({ ...draft, lastName: e.target.value })} required />
            <Field label="Date of birth" type="date" value={draft.dateOfBirth} onChange={(e) => setDraft({ ...draft, dateOfBirth: e.target.value })} />
            <NationalitySelect value={draft.nationality} onChange={(v) => setDraft({ ...draft, nationality: v })} />
            <Field label="Passport number" value={draft.passportNumber} onChange={(e) => setDraft({ ...draft, passportNumber: e.target.value })} />
            <Field label="Passport expiry" type="date" value={draft.passportExpiry} onChange={(e) => setDraft({ ...draft, passportExpiry: e.target.value })} />
          </div>
          <div className="mt-3 flex gap-2">
            <Button type="submit" busy={busy} size="md">Save traveller</Button>
            <Button type="button" variant="secondary" onClick={() => setAdding(false)}>Cancel</Button>
          </div>
        </form>
      ) : (
        <button type="button" onClick={() => setAdding(true)} className="mt-3 text-sm font-semibold text-brand-700 hover:underline">
          + Add traveller
        </button>
      )}
    </Section>
  );
}

function ChangePassword() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const policyMet = passwordPolicyMet(next);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setTouched(true);
    setError(null);
    setDone(false);
    if (!policyMet) return;
    setBusy(true);
    try {
      await profileApi.changePassword(current, next);
      setCurrent('');
      setNext('');
      setTouched(false);
      setDone(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Section title="Password" subtitle="Change the password you sign in with.">
      <form onSubmit={submit} noValidate className="space-y-4">
        <ErrorAlert error={error} />
        {done ? <Alert tone="info">Your password has been changed.</Alert> : null}
        <Field label="Current password" type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" required />
        <Field
          label="New password"
          type="password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          autoComplete="new-password"
          required
          hint={
            <ul className="mt-1 space-y-0.5">
              {PASSWORD_RULES.map((rule) => {
                const met = rule.test(next);
                return (
                  <li key={rule.label} className={met ? 'text-emerald-700' : touched ? 'text-red-600' : 'text-slate-500'}>
                    <span aria-hidden="true">{met ? '✓' : '•'}</span> {rule.label}
                  </li>
                );
              })}
            </ul>
          }
        />
        <Button type="submit" busy={busy}>Update password</Button>
      </form>
    </Section>
  );
}
