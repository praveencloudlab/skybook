import { Link } from 'react-router-dom';
import { useEffect, useState, type FormEvent } from 'react';
import { NATIONALITIES, bookingsApi } from '../../api/bookings';
import { flightsApi } from '../../api/flights';
import { LANGUAGES, setLanguage, type LanguageCode } from '../../lib/i18n';
import { DISPLAY_CURRENCIES, price, setDisplayCurrency } from '../../lib/format';
import { fareAlertsApi, type FareAlert } from '../../api/alerts';
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

  const firstName = profile?.fullName?.trim().split(/\s+/)[0];

  return (
    <main className="mx-auto max-w-3xl px-4 sm:px-6 py-8">
      <h1 className="display text-3xl text-slate-900">
        Welcome{firstName ? `, ${firstName}` : ''}
      </h1>
      <p className="mt-1 text-sm text-slate-500">
        Your account at a glance — details on file are reused to fill in bookings so you don't
        retype them. Your trips live under{' '}
        <span className="font-semibold text-slate-700">My trips</span>.
      </p>

      <div className="mt-6 space-y-6">
        <ErrorAlert error={error} />
        {profile ? (
          <>
            <NextTripCard />
            <FareWatchesCard />
            <AccountOverview profile={profile} />
            <PreferencesCard profile={profile} onSaved={setProfile} />
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

/**
 * The account hub's action layer (passenger features): the soonest upcoming
 * trip as ONE card pointing into My trips - never a second trips list - and
 * nudges only when they are true (check-in open, payment pending). Silence
 * when there is nothing to do.
 */
function NextTripCard() {
  const [next, setNext] = useState<{
    booking: import('../../api/bookings').Booking;
    flight: import('../../api/flights').Flight;
  } | null | undefined>(undefined);

  useEffect(() => {
    const controller = new AbortController();
    (async () => {
      try {
        const bookings = (await bookingsApi.mine(controller.signal)).filter(
          (b) => b.bookingStatus === 'CONFIRMED' || b.bookingStatus === 'CREATED' || b.bookingStatus === 'PARTIALLY_CANCELLED',
        );
        // Soonest future departure across candidates - bounded lookups.
        let best: { booking: (typeof bookings)[number]; flight: import('../../api/flights').Flight } | null = null;
        for (const b of bookings.slice(0, 8)) {
          try {
            const flight = await flightsApi.byId(b.flightId, controller.signal);
            if (new Date(flight.departureTime) > new Date()
                && (!best || flight.departureTime < best.flight.departureTime)) {
              best = { booking: b, flight };
            }
          } catch { /* skip unfetchable flight */ }
        }
        setNext(best);
      } catch {
        setNext(null);
      }
    })();
    return () => controller.abort();
  }, []);

  if (next === undefined) return null;
  if (next === null) return null;

  const { booking, flight } = next;
  const dep = new Date(flight.departureTime);
  const hoursToGo = (dep.getTime() - Date.now()) / 36e5;
  const anyNotCheckedIn = booking.passengers.some(
    (p) => !p.cancelled && (p.segmentIndex ?? 0) === 0 && p.checkInStatus !== 'CHECKED_IN' && p.checkInStatus !== 'BOARDED',
  );
  const checkInOpen = hoursToGo <= 24 && hoursToGo > 0.75 && anyNotCheckedIn && booking.bookingStatus === 'CONFIRMED';
  const paymentPending = booking.bookingStatus === 'CREATED';

  return (
    <section className="overflow-hidden rounded-2xl bg-brand-950 text-white shadow-[var(--shadow-card)]">
      <div className="flex flex-wrap items-center justify-between gap-4 px-6 py-5">
        <div>
          <div className="text-[11px] font-semibold uppercase tracking-wider text-white/50">Your next trip</div>
          <div className="mt-1 text-xl font-bold">
            {flight.originAirportCode} → {flight.destinationAirportCode}
            <span className="ml-2 text-sm font-medium text-white/60">
              {flight.flightNumber} · {flight.departureTime.slice(0, 10)} · {flight.departureTime.slice(11, 16)}
              {flight.departureTerminal ? ` · Terminal ${flight.departureTerminal}` : ''}
            </span>
          </div>
          <div className="tabular mt-0.5 text-xs text-white/50">PNR {booking.bookingReference}</div>
        </div>
        <div className="flex items-center gap-2">
          {checkInOpen ? (
            <span className="rounded-full bg-emerald-400/20 px-3 py-1 text-xs font-bold text-emerald-300">
              Check-in is open
            </span>
          ) : null}
          {paymentPending ? (
            <span className="rounded-full bg-amber-400/20 px-3 py-1 text-xs font-bold text-amber-300">
              Payment pending
            </span>
          ) : null}
          <Link to={`/bookings?open=${booking.id}`}
            className="rounded-full bg-accent-500 px-5 py-2 text-sm font-bold text-white transition hover:bg-accent-600">
            {checkInOpen ? 'Check in' : 'View trip'}
          </Link>
        </div>
      </div>
    </section>
  );
}

/** The routes this account watches - repriced hourly server-side; emails on movement. */
function FareWatchesCard() {
  const [alerts, setAlerts] = useState<FareAlert[] | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    fareAlertsApi.mine(controller.signal).then(setAlerts).catch(() => setAlerts([]));
    return () => controller.abort();
  }, []);

  if (!alerts || alerts.length === 0) return null;

  return (
    <section className="rounded-2xl bg-white p-6 shadow-[var(--shadow-card)]">
      <h2 className="text-sm font-bold text-slate-900">Fare watches</h2>
      <p className="mt-0.5 text-xs text-slate-500">
        Repriced hourly with the same fares checkout uses — you'll get an email when one moves.
      </p>
      <ul className="mt-3 divide-y divide-slate-100">
        {alerts.map((a) => (
          <li key={a.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
            <span>
              <span className="font-bold text-slate-900">{a.originAirportCode} → {a.destinationAirportCode}</span>
              <span className="ml-2 text-slate-500">{a.travelDate} · {a.travelClass.toLowerCase().replace('_', ' ')}</span>
            </span>
            <span className="flex items-center gap-3">
              <span className="tabular font-bold text-slate-900">{price(a.currentFare, a.currency)}</span>
              <button type="button"
                onClick={() => fareAlertsApi.remove(a.id).then(() => setAlerts(alerts.filter((x) => x.id !== a.id)))}
                className="text-xs font-medium text-slate-400 hover:text-red-600">
                Stop watching
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Language + currency saved as ACCOUNT facts - applied on every sign-in, any device. */
function PreferencesCard({ profile, onSaved }: { profile: Profile; onSaved: (p: Profile) => void }) {
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  async function save(patch: { preferredLanguage?: string; preferredCurrency?: string }) {
    setSaving(true);
    setSaved(false);
    try {
      const updated = await profileApi.update(patch);
      onSaved(updated);
      if (patch.preferredLanguage) setLanguage(patch.preferredLanguage as LanguageCode);
      if (patch.preferredCurrency) setDisplayCurrency(patch.preferredCurrency);
      setSaved(true);
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="rounded-2xl bg-white p-6 shadow-[var(--shadow-card)]">
      <h2 className="text-sm font-bold text-slate-900">Preferences</h2>
      <p className="mt-0.5 text-xs text-slate-500">
        Saved to your account and applied whenever you sign in — on any device.
      </p>
      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Language</span>
          <select value={profile.preferredLanguage ?? ''} disabled={saving}
            onChange={(e) => void save({ preferredLanguage: e.target.value })}
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500">
            <option value="" disabled>Not set (device default)</option>
            {LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.name}</option>)}
          </select>
        </label>
        <label className="text-sm">
          <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-slate-500">Currency</span>
          <select value={profile.preferredCurrency ?? ''} disabled={saving}
            onChange={(e) => void save({ preferredCurrency: e.target.value })}
            className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm outline-none focus:border-brand-500">
            <option value="" disabled>Not set (device default)</option>
            {DISPLAY_CURRENCIES.map((c) => <option key={c.code} value={c.code}>{c.label}</option>)}
          </select>
        </label>
      </div>
      {saved ? <p className="mt-2 text-xs font-medium text-emerald-700">Saved — applied to this session too.</p> : null}
    </section>
  );
}

/**
 * Everything the account holds, read-only at a glance - the editable forms
 * below change it. Passport number is shown masked; the edit form has the
 * full value.
 */
function AccountOverview({ profile }: { profile: Profile }) {
  const mask = (value: string | null) =>
    value ? `${'•'.repeat(Math.max(value.length - 3, 0))}${value.slice(-3)}` : '—';
  const rows: Array<[string, string]> = [
    ['Full name', profile.fullName ?? '—'],
    ['Email', profile.email],
    ['Role', profile.role === 'ROLE_ADMIN' ? 'Administrator' : 'Passenger'],
    ['Phone', profile.phone ?? '—'],
    ['Date of birth', profile.dateOfBirth ?? '—'],
    ['Nationality', profile.nationality ?? '—'],
    ['Passport', mask(profile.passportNumber)],
    ['Passport expiry', profile.passportExpiry ?? '—'],
    ['Emergency contact', profile.emergencyContactName ?? '—'],
    ['Emergency phone', profile.emergencyContactPhone ?? '—'],
  ];

  return (
    <section className="card overflow-hidden">
      <div className="flex items-center gap-3 border-b border-slate-100 bg-brand-950 px-5 py-4 text-white">
        <span className="grid h-11 w-11 place-items-center rounded-full bg-accent-500 text-base font-bold">
          {(profile.fullName ?? profile.email)
            .split(/\s+/)
            .map((part) => part[0])
            .slice(0, 2)
            .join('')
            .toUpperCase()}
        </span>
        <div className="min-w-0">
          <div className="truncate text-base font-bold">{profile.fullName ?? profile.email}</div>
          <div className="truncate text-xs text-white/70">{profile.email}</div>
        </div>
      </div>
      <dl className="grid gap-x-6 px-5 py-4 text-sm sm:grid-cols-2">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-baseline justify-between gap-3 border-b border-slate-50 py-1.5">
            <dt className="shrink-0 text-slate-500">{label}</dt>
            <dd className="tabular truncate text-right font-semibold text-slate-900">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
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
  const [dirty, setDirty] = useState(false);

  function set<K extends keyof typeof form>(key: K, value: string) {
    setForm((f) => ({ ...f, [key]: value }));
    setSaved(false);
    setDirty(true);
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
      setDirty(false);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit}>
      {/* A sticky action bar so saving is never missed - the previous single
          button lived at the bottom of the third section and people edited then
          navigated away without pressing it, so their changes looked "not saved". */}
      {/* -mx-6/px-6 bleeds this bar to the card's edges - but the card's own
          padding is 16 px on a phone, so a 24 px bleed overhung the layout by
          8 px and gave the whole page a horizontal scroll. The bleed now
          matches the padding at each size. */}
      <div className="sticky top-14 z-10 -mx-4 mb-4 flex items-center justify-between gap-3 border-b border-slate-200 bg-white/90 px-4 py-2.5 backdrop-blur sm:-mx-6 sm:px-6">
        <span className="text-sm text-slate-500">
          {dirty ? 'You have unsaved changes' : saved ? 'All changes saved ✓' : 'Your saved details'}
        </span>
        <Button type="submit" busy={busy} disabled={!dirty} size="md">
          Save changes
        </Button>
      </div>

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
