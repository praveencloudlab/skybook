import { useEffect, useRef, useState } from 'react';
import {
  BrowserRouter,
  Link,
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
  useSearchParams,
} from 'react-router-dom';
import { authApi } from './api/auth';
import { setUnauthenticatedHandler } from './api/client';
import { useSession } from './features/auth/useSession';
import { RegisterPage } from './features/auth/RegisterPage';
import { SignInPage } from './features/auth/SignInPage';
import { SignInForm } from './features/auth/SignInForm';
import { ForgotPasswordPage } from './features/auth/ForgotPasswordPage';
import { ResetPasswordPage } from './features/auth/ResetPasswordPage';
import { LandingPage } from './features/home/LandingPage';
import { ProfilePage } from './features/profile/ProfilePage';
import { AdminPage } from './features/admin/AdminPage';
import { ErrorPage } from './features/errors/ErrorPage';
import { RouteErrorBoundary } from './features/errors/RouteErrorBoundary';
import { SearchPage } from './features/search/SearchPage';
import { SiteFooter } from './components/SiteFooter';
import { FlightQuotePage } from './features/search/FlightQuotePage';
import { SeatSelectionPage } from './features/seats/SeatSelectionPage';
import { GuestsPage } from './features/booking/GuestsPage';
import { BagsPage, EXTRA_BAG_FEE } from './features/booking/BagsPage';
import { PaymentPage } from './features/booking/PaymentPage';
import { emptyPassenger, type PassengerDraft } from './features/booking/PassengerForm';
import { ConfirmationPage } from './features/booking/ConfirmationPage';
import { MyBookingsPage } from './features/bookings/MyBookingsPage';
import { BookingDetailPage } from './features/bookings/BookingDetailPage';
import type { AircraftSeat } from './api/seats';
import type { Booking, PassengerType } from './api/bookings';
import { ONE_ADULT, totalTravellers, type Travellers } from './components/TravellersPicker';
import type { Payment } from './api/payments';
import type { FareType, TravelClass } from './api/quotes';
import type { Flight } from './api/flights';
import { session } from './lib/session';
import { t, LANGUAGES, currentLanguage, setLanguage, useLocale, type LanguageCode } from './lib/i18n';
import { DISPLAY_CURRENCIES, displayCurrency, setDisplayCurrency } from './lib/format';

/**
 * App shell (FRONTEND_MODULE.md §2, §4).
 *
 * <p>Two things are wired here rather than per screen, because they must be true
 * everywhere: the session is established once on load, and any 401 from any call
 * routes to sign-in while remembering where the user was.
 */
function SessionBootstrap({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { resolved } = useSession();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    // One 401 handler for the whole app. Screens never deal with expiry; they
    // just make their call and this catches the fallout.
    setUnauthenticatedHandler(() => {
      session.setReturnTo(location.pathname + location.search);
      navigate('/sign-in', { replace: true });
    });
  }, [navigate, location]);

  useEffect(() => {
    // A returning visitor arrives with a valid cookie and no client state, so we
    // have to ask who they are - the credential is httpOnly and unreadable here.
    void authApi.restore().finally(() => setReady(true));
  }, []);

  // Hold the first paint until we know. Rendering "Sign in" and then swapping it
  // for the user's name a moment later makes a signed-in visitor look
  // signed-out on every single page load.
  if (!ready && !resolved) {
    return (
      <div className="grid min-h-full place-items-center text-sm text-slate-500">Loading…</div>
    );
  }

  return <>{children}</>;
}

function Header() {
  const { signedIn, subject, isAdmin } = useSession();
  const navigate = useNavigate();

  async function signOut() {
    // Must be a server call: the cookie is httpOnly, so the browser cannot
    // delete it itself.
    await authApi.logout();
    navigate('/sign-in', { replace: true });
  }

  return (
    <header className="glass sticky top-0 z-20 text-white">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-3.5">
        <Link to="/" className="group flex items-center gap-2.5">
          {/* A mark, not just a wordmark - it is what makes the header read as
              an airline rather than an admin console. */}
          <span className="grid h-9 w-9 place-items-center rounded-full bg-accent-500 transition group-hover:bg-accent-400">
            <svg viewBox="0 0 24 24" className="h-4.5 w-4.5 fill-white" aria-hidden="true">
              <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
            </svg>
          </span>
          <span className="text-lg font-bold tracking-tight text-white">SkyBook</span>
        </Link>

        <nav className="flex items-center gap-1 text-sm sm:gap-2">
          {/* Language + display currency. Both persist and reload so every
              t()/price() call site repaints - a settings change, not a hot path. */}
          <select
            aria-label="Language"
            value={currentLanguage()}
            onChange={(e) => setLanguage(e.target.value as LanguageCode)}
            className="rounded-lg border border-white/20 bg-transparent px-1.5 py-1 text-xs font-medium text-white/80 transition hover:bg-white/10 [&>option]:text-slate-900"
          >
            {LANGUAGES.map((l) => (
              <option key={l.code} value={l.code}>{l.name}</option>
            ))}
          </select>
          <select
            aria-label="Currency"
            value={displayCurrency()}
            onChange={(e) => setDisplayCurrency(e.target.value)}
            className="mr-1 rounded-lg border border-white/20 bg-transparent px-1.5 py-1 text-xs font-medium text-white/80 transition hover:bg-white/10 [&>option]:text-slate-900"
          >
            {DISPLAY_CURRENCIES.map((c) => (
              <option key={c.code} value={c.code}>{c.label}</option>
            ))}
          </select>
          {/* Admins get an operations-only menu; passengers get the booking menu. */}
          {isAdmin ? (
            <Link
              to="/admin"
              className="rounded-lg px-2.5 py-1.5 font-semibold text-accent-200 transition hover:bg-white/10 hover:text-accent-100"
            >
              Admin console
            </Link>
          ) : (
            <Link
              to="/search"
              onClick={() => sessionStorage.removeItem(JOURNEY_KEY)}
              className="rounded-lg px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
            >
              {t('nav.search')}
            </Link>
          )}
          {signedIn ? (
            <>
              {!isAdmin ? (
                <Link
                  to="/bookings"
                  className="rounded-lg px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
                >
                  {t('nav.trips')}
                </Link>
              ) : null}
              <Link
                to="/profile"
                className="hidden max-w-[16ch] truncate rounded-lg px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white sm:inline-block"
                title={subject ?? ''}
              >
                {subject}
              </Link>
              <button
                type="button"
                onClick={signOut}
                className="rounded-lg px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
              >
                {t('nav.signout')}
              </button>
            </>
          ) : (
            <Link
              to="/sign-in"
              className="rounded-lg bg-white/10 px-3.5 py-1.5 font-semibold text-white ring-1 ring-white/20 transition hover:bg-white/20"
            >
              {t('nav.signin')}
            </Link>
          )}
        </nav>
      </div>
    </header>
  );
}

/** Gate for screens that need a session. */
function RequireSession({ children }: { children: React.ReactNode }) {
  const { signedIn } = useSession();
  const location = useLocation();

  if (!signedIn) {
    session.setReturnTo(location.pathname + location.search);
    return <Navigate to="/sign-in" replace />;
  }
  return <>{children}</>;
}

/**
 * Gate for admin screens. Cosmetic only - the server enforces ADMIN on every
 * admin endpoint regardless - but it keeps a passenger out of a console they
 * could not use and would only collect 403s in. A signed-in non-admin gets the
 * 403 page rather than a redirect, because the answer is "not you", not "sign in".
 */
function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { signedIn, isAdmin } = useSession();
  const location = useLocation();

  if (!signedIn) {
    session.setReturnTo(location.pathname + location.search);
    return <Navigate to="/sign-in" replace />;
  }
  if (!isAdmin) {
    return <ErrorPage code="403" />;
  }
  return <>{children}</>;
}

/** What the passenger has chosen so far, as they move through the journey. */
interface FareChoice {
  cabin: TravelClass;
  fare: FareType;
  baseFare: number;
  currency: string;
}

/** Where the passenger is in the booking journey. */
type Step = 'search' | 'fares' | 'guests' | 'seat' | 'seatConnection' | 'seatReturn' | 'bags' | 'payment' | 'confirmed';

/**
 * The in-progress booking, persisted to sessionStorage so it survives every
 * route away from /search - the header's Log in, Create an account, a
 * mid-booking 401 redirect, even a refresh. Without this the journey's local
 * state (and the traveller's chosen flight and fare with it) was destroyed by
 * any navigation, and only the inline auth-gate login preserved it.
 *
 * <p>Passport numbers and expiries are deliberately NOT written to storage -
 * names and dates of birth restore, passports are re-asked. If the saved step
 * was already past the guests form, restoring clamps back to it so the
 * missing passports are collected before anything is bookable.
 */
interface JourneyDraft {
  step: Step;
  flight: Flight;
  travellers: Travellers;
  preferredCabin: TravelClass;
  choice: FareChoice | null;
  returnFlight?: Flight | null;
  /** Same-carrier through-ticket: onward connection legs after `flight`. */
  connection?: Flight[];
  seats: AircraftSeat[];
  returnSeats?: AircraftSeat[];
  /** Seat picks per through-ticket connection leg, in leg order. */
  connectionSeats?: AircraftSeat[][];
  guests: PassengerDraft[];
  bags: number[];
  contactEmail: string;
}

const JOURNEY_KEY = 'skybook.journeyDraft';

function loadJourneyDraft(): JourneyDraft | null {
  try {
    const raw = sessionStorage.getItem(JOURNEY_KEY);
    if (!raw) {
      return null;
    }
    const draft = JSON.parse(raw) as JourneyDraft;
    if (!draft || !draft.flight || !draft.step || draft.step === 'confirmed') {
      return null;
    }
    // Storage never holds passports; a draft resumed beyond the guests form
    // goes back to it so they are re-collected.
    const passportMissing =
      Array.isArray(draft.guests) && draft.guests.some((g) => !g.passportNumber);
    if (passportMissing
        && (draft.step === 'seat' || draft.step === 'seatConnection' || draft.step === 'seatReturn' || draft.step === 'bags' || draft.step === 'payment')) {
      draft.step = 'guests';
    }
    return draft;
  } catch {
    return null;
  }
}

/**
 * The log-in wall that appears the moment an anonymous browser tries to book.
 *
 * <p>Rendered INSIDE HomePage rather than as a route redirect, on purpose: the
 * chosen flight and fare live in HomePage's local state, and bouncing to
 * /sign-in would throw them away. Signing in here mutates the session, HomePage
 * re-renders, and the journey resumes exactly where it paused - the selection
 * intact. It shows what they're about to book so the interruption feels like a
 * checkpoint, not a dead end.
 */
function BookingAuthGate({
  flight,
  choice,
  onBack,
}: {
  flight: Flight | null;
  choice: FareChoice | null;
  onBack: () => void;
}) {
  // If they log in via the HEADER (or register) instead of the inline form,
  // the post-login redirect must bring them back here - the persisted draft
  // does the rest.
  useEffect(() => {
    session.setReturnTo('/search');
  }, []);

  return (
    <main className="mx-auto max-w-md px-6 py-12">
      <button
        type="button"
        onClick={onBack}
        className="mb-6 inline-flex items-center gap-1 text-sm font-medium text-slate-500 hover:text-slate-700"
      >
        ← Back to fares
      </button>

      <div className="card p-6">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-brand-50 px-2.5 py-1 text-xs font-medium text-brand-700">
          ✦ One step to book
        </span>
        <h1 className="mt-3 text-xl font-semibold tracking-tight text-slate-900">
          Log in to choose your seat
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          Searching is open to everyone. To hold a seat and book, you'll need an account - your
          selection is saved while you sign in.
        </p>

        {flight && choice ? (
          <div className="tabular mt-4 flex items-center justify-between rounded-xl bg-slate-50 px-3.5 py-2.5 text-sm">
            <span className="font-semibold text-slate-900">
              {flight.originAirportCode} → {flight.destinationAirportCode}
            </span>
            <span className="text-slate-500">
              {flight.flightNumber} · {choice.cabin.replace('_', ' ').toLowerCase()}
            </span>
          </div>
        ) : null}

        <div className="mt-6">
          {/* onSignedIn is intentionally a no-op: the session change alone
              re-renders HomePage past this gate. */}
          <SignInForm onSignedIn={() => undefined} />
        </div>

        <p className="mt-5 text-center text-sm text-slate-600">
          New to SkyBook?{' '}
          <Link to="/register" className="font-medium text-brand-700 hover:underline">
            Create an account
          </Link>
        </p>
      </div>
    </main>
  );
}

function BookingJourney() {
  const { signedIn } = useSession();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  // One-time hydration from a persisted draft (see JourneyDraft above) - but
  // an explicit deep-linked search (?from&to: landing hero, popular
  // destination cards) is a NEW search intent and beats any saved draft.
  const [draft] = useState(() => {
    if (searchParams.get('from') && searchParams.get('to')) {
      sessionStorage.removeItem(JOURNEY_KEY);
      return null;
    }
    return loadJourneyDraft();
  });
  // Kept in local state rather than routes: these are steps within one search,
  // and a /flights/:id route would re-fetch (and lose the results behind it) on
  // every back-navigation.
  const [step, setStep] = useState<Step>(draft?.step ?? 'search');
  const [flight, setFlight] = useState<Flight | null>(draft?.flight ?? null);
  const [returnFlight, setReturnFlight] = useState<Flight | null>(draft?.returnFlight ?? null);
  const [connection, setConnection] = useState<Flight[]>(draft?.connection ?? []);
  const [travellers, setTravellers] = useState<Travellers>(draft?.travellers ?? ONE_ADULT);
  // The cabin chosen in the search widget - preselects (never hides) a cabin
  // on the fare step.
  const [preferredCabin, setPreferredCabin] = useState<TravelClass>(draft?.preferredCabin ?? 'ECONOMY');
  const [choice, setChoice] = useState<FareChoice | null>(draft?.choice ?? null);
  // One chosen seat per traveller, in passenger order; shorter than the party
  // means the rest are auto-assigned free at check-in.
  const [seats, setSeats] = useState<AircraftSeat[]>(draft?.seats ?? []);
  // Round trip: the return leg's picks, chosen on its own seat map.
  const [returnSeats, setReturnSeats] = useState<AircraftSeat[]>(draft?.returnSeats ?? []);
  const [connSeats, setConnSeats] = useState<AircraftSeat[][]>(draft?.connectionSeats ?? []);
  // Which through-ticket connection leg's seat map is open (0-based).
  const [connLeg, setConnLeg] = useState(0);
  // Guest details, extra bags and the booking contact live at the journey
  // level: the carrier flow spreads them across separate steps (guests ->
  // seats -> bags -> payment) and each later step reads what earlier ones set.
  const [guests, setGuests] = useState<PassengerDraft[]>(draft?.guests ?? [emptyPassenger()]);
  const [bags, setBags] = useState<number[]>(draft?.bags ?? [0]);
  const [contactEmail, setContactEmail] = useState(draft?.contactEmail ?? '');
  const [result, setResult] = useState<{ booking: Booking; payment: Payment } | null>(null);

  const paxCount = totalTravellers(travellers);
  // Adults first, then children, then infants - the order checkout renders
  // its forms in, so each one is labelled and DOB-bounded for the right type.
  const paxTypes: PassengerType[] = [
    ...Array.from({ length: travellers.adults }, () => 'ADULT' as const),
    ...Array.from({ length: travellers.children }, () => 'CHILD' as const),
    ...Array.from({ length: travellers.infants }, () => 'INFANT' as const),
  ];

  // Clicking Search (a bare /search navigation) mid-journey means 'start
  // again' - reset everything, including the stored draft via restart().
  const firstNav = useRef(true);
  useEffect(() => {
    if (firstNav.current) {
      firstNav.current = false;
      return;
    }
    if (!searchParams.get('from') && step !== 'search') {
      restart();
    }
    // Reacting to real navigations only, not journey state changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.key]);

  // Persist the draft on every meaningful change; drop it once there is
  // nothing to resume (back at search, or the booking is made). Passports are
  // stripped before writing - see JourneyDraft.
  useEffect(() => {
    if (step === 'search' || step === 'confirmed' || !flight) {
      sessionStorage.removeItem(JOURNEY_KEY);
      return;
    }
    const persisted: JourneyDraft = {
      step,
      flight,
      travellers,
      preferredCabin,
      choice,
      returnFlight,
      connection,
      seats,
      returnSeats,
      connectionSeats: connSeats,
      bags,
      contactEmail,
      guests: guests.map((g) => ({ ...g, passportNumber: '', passportExpiry: '' })),
    };
    try {
      sessionStorage.setItem(JOURNEY_KEY, JSON.stringify(persisted));
    } catch {
      // Storage full/blocked: the journey still works, it just won't survive
      // a navigation - the pre-persistence behaviour.
    }
  }, [step, flight, returnFlight, connection, travellers, preferredCabin, choice, seats, returnSeats, guests, bags, contactEmail]);

  function restart() {
    sessionStorage.removeItem(JOURNEY_KEY);
    setStep('search');
    setFlight(null);
    setReturnFlight(null);
    setConnection([]);
    setTravellers(ONE_ADULT);
    setChoice(null);
    setSeats([]);
    setReturnSeats([]);
    setConnSeats([]);
    setConnLeg(0);
    setGuests([emptyPassenger()]);
    setBags([0]);
    setContactEmail('');
    setResult(null);
  }

  // Shared money math: every step's Summary rail shows the same running total,
  // computed one way. Seats align to the SEATED party (lap infants hold none),
  // so they are re-aligned to guest order for labels and the final request.
  const seatedIndexes = paxTypes
    .map((type, index) => (type !== 'INFANT' ? index : -1))
    .filter((index) => index >= 0);
  const alignedSeats: (AircraftSeat | undefined)[] = [];
  seats.forEach((seat, seatedIdx) => {
    const guestIdx = seatedIndexes[seatedIdx];
    if (guestIdx !== undefined) {
      alignedSeats[guestIdx] = seat;
    }
  });
  const alignedReturnSeats: (AircraftSeat | undefined)[] = [];
  returnSeats.forEach((seat, seatedIdx) => {
    const guestIdx = seatedIndexes[seatedIdx];
    if (guestIdx !== undefined) {
      alignedReturnSeats[guestIdx] = seat;
    }
  });
  // Flexi/Premium entitlement: seat surcharges are waived (server does the
  // same at booking), so only Saver totals them.
  const seatCharge = (s: AircraftSeat) =>
    choice && choice.fare !== 'SAVER' ? 0 : Number(s.listedSurcharge) || 0;
  const seatTotal =
    seats.reduce((sum, s) => sum + seatCharge(s), 0) +
    returnSeats.reduce((sum, s) => sum + seatCharge(s), 0) +
    connSeats.flat().reduce((sum, s) => sum + seatCharge(s), 0);
  const tripLegs = returnFlight ? 2 : 1;
  // Bags fly both directions on a round trip, charged per leg.
  const bagTotal = bags.reduce((sum, b) => sum + b, 0) * EXTRA_BAG_FEE * tripLegs;
  const total = (choice ? choice.baseFare * paxCount : 0) + seatTotal + bagTotal;
  const guestLabel = (index: number) =>
    `${guests[index]?.firstName ?? ''} ${guests[index]?.lastName ?? ''}`.trim() || `Guest ${index + 1}`;
  const extras = [
    ...alignedSeats.flatMap((seat, index) =>
      seat
        ? [{ label: `${guestLabel(index)} · Seat ${seat.seatNumber}${tripLegs > 1 ? ' (out)' : ''}`, amount: seatCharge(seat) }]
        : [],
    ),
    ...alignedReturnSeats.flatMap((seat, index) =>
      seat
        ? [{ label: `${guestLabel(index)} · Seat ${seat.seatNumber} (return)`, amount: seatCharge(seat) }]
        : [],
    ),
    ...connSeats.flatMap((legSeats, legIdx) =>
      legSeats.flatMap((seat, seatedIdx) => {
        const guestIdx = seatedIndexes[seatedIdx];
        return seat && guestIdx !== undefined
          ? [{ label: `${guestLabel(guestIdx)} · Seat ${seat.seatNumber} (leg ${legIdx + 2})`, amount: seatCharge(seat) }]
          : [];
      }),
    ),
    ...bags.flatMap((count, index) =>
      count > 0
        ? [{ label: `${guestLabel(index)} · ${count} extra bag${count > 1 ? 's' : ''}${tripLegs > 1 ? ' × 2 tickets' : ''}`, amount: count * EXTRA_BAG_FEE * tripLegs }]
        : [],
    ),
  ];

  // Any in-progress step carries an explicit exit: restart() clears the
  // persisted draft too, so 'Start a new search' can never resume again.
  const newSearchBar =
    step !== 'search' && step !== 'confirmed' && flight ? (
      <div className="flex items-center justify-between gap-3 border-b border-white/10 bg-brand-900 px-6 py-1.5 text-xs text-white/80">
        <span className="tabular truncate">
          Booking in progress: {flight.originAirportCode} → {flight.destinationAirportCode} ·{' '}
          {flight.departureTime.slice(0, 10)}
        </span>
        <button
          type="button"
          onClick={restart}
          className="shrink-0 font-bold text-accent-300 underline-offset-2 hover:underline"
        >
          Start a new search
        </button>
      </div>
    ) : null;

  // Search and fares are public; everything from guest details on writes owned
  // data and needs a principal. An anonymous browser that reaches those steps
  // meets the log-in wall here - with its flight and fare still in state, so it
  // resumes intact the instant the session appears.
  if ((step === 'guests' || step === 'seat' || step === 'seatConnection' || step === 'seatReturn' || step === 'bags' || step === 'payment' || step === 'confirmed') && !signedIn) {
    return <BookingAuthGate flight={flight} choice={choice} onBack={() => setStep('fares')} />;
  }

  if (step === 'confirmed' && result) {
    return (
      <ConfirmationPage booking={result.booking} payment={result.payment} onDone={restart} />
    );
  }

  if (step === 'payment' && flight && choice) {
    return (
      <>
      {newSearchBar}
      <PaymentPage
        flight={flight}
        cabin={choice.cabin}
        fare={choice.fare}
        currency={choice.currency}
        travellers={travellers}
        guests={guests}
        seats={alignedSeats as AircraftSeat[]}
        returnSeats={alignedReturnSeats as AircraftSeat[]}
        returnFlight={returnFlight}
        connection={connection}
        connectionSeats={connSeats.map((legSeats) => {
          const aligned: (AircraftSeat | undefined)[] = [];
          legSeats.forEach((seat, seatedIdx) => {
            const guestIdx = seatedIndexes[seatedIdx];
            if (guestIdx !== undefined) {
              aligned[guestIdx] = seat;
            }
          });
          return aligned as AircraftSeat[];
        })}
        bags={bags}
        contactEmail={contactEmail}
        extras={extras}
        total={total}
        onBack={() => setStep('bags')}
        onBooked={(booking, payment) => {
          setResult({ booking, payment });
          setStep('confirmed');
        }}
      />
      </>
    );
  }

  if (step === 'bags' && flight && choice) {
    return (
      <>
      {newSearchBar}
      <BagsPage
        flight={flight}
        cabin={choice.cabin}
        fare={choice.fare}
        currency={choice.currency}
        travellers={travellers}
        paxTypes={paxTypes}
        guests={guests}
        bags={bags}
        onAdjustBag={(index, delta) =>
          setBags((prev) => prev.map((b, i) => (i === index ? Math.max(0, Math.min(5, b + delta)) : b)))
        }
        extras={extras}
        total={total}
        onBack={() => {
          if (connection.length) {
            setConnLeg(connection.length - 1);
            setStep('seatConnection');
          } else {
            setStep(returnFlight ? 'seatReturn' : 'seat');
          }
        }}
        onContinue={() => setStep('payment')}
      />
      </>
    );
  }

  if (step === 'seat' && flight && choice) {
    return (
      <>
      {newSearchBar}
      <SeatSelectionPage
        flight={flight}
        legLabel={returnFlight ? 'Outbound' : connection.length ? 'Leg 1' : undefined}
        cabin={choice.cabin}
        fare={choice.fare}
        baseFare={choice.baseFare}
        currency={choice.currency}
        travellers={travellers}
        paxTypes={paxTypes}
        guests={guests}
        onBack={() => setStep('guests')}
        onContinue={(chosen) => {
          setSeats(chosen);
          if (connection.length) {
            setConnLeg(0);
            setStep('seatConnection');
          } else {
            setStep(returnFlight ? 'seatReturn' : 'bags');
          }
        }}
      />
      </>
    );
  }

  // Through-ticket: every onward connection leg gets its OWN seat map too
  // (its own flight, availability and surcharges), walked one leg at a time.
  if (step === 'seatConnection' && connection.length > 0 && choice) {
    const legFlight = connection[Math.min(connLeg, connection.length - 1)];
    return (
      <>
      {newSearchBar}
      <SeatSelectionPage
        key={legFlight.id}
        flight={legFlight}
        legLabel={`Leg ${connLeg + 2}`}
        cabin={choice.cabin}
        fare={choice.fare}
        baseFare={choice.baseFare}
        currency={choice.currency}
        travellers={travellers}
        paxTypes={paxTypes}
        guests={guests}
        onBack={() => {
          if (connLeg > 0) {
            setConnLeg(connLeg - 1);
          } else {
            setStep('seat');
          }
        }}
        onContinue={(chosen) => {
          setConnSeats((prev) => {
            const next = [...prev];
            next[connLeg] = chosen;
            return next;
          });
          if (connLeg + 1 < connection.length) {
            setConnLeg(connLeg + 1);
          } else {
            setStep('bags');
          }
        }}
      />
      </>
    );
  }

  // Round trip: the return leg gets its OWN seat map (its own flight, its
  // own availability and surcharges).
  if (step === 'seatReturn' && returnFlight && choice) {
    return (
      <>
      {newSearchBar}
      <SeatSelectionPage
        flight={returnFlight}
        legLabel="Return"
        cabin={choice.cabin}
        fare={choice.fare}
        baseFare={choice.baseFare}
        currency={choice.currency}
        travellers={travellers}
        paxTypes={paxTypes}
        guests={guests}
        onBack={() => setStep('seat')}
        onContinue={(chosen) => {
          setReturnSeats(chosen);
          setStep('bags');
        }}
      />
      </>
    );
  }

  if (step === 'guests' && flight && choice) {
    return (
      <>
      {newSearchBar}
      <GuestsPage
        flight={flight}
        cabin={choice.cabin}
        fare={choice.fare}
        currency={choice.currency}
        travellers={travellers}
        paxTypes={paxTypes}
        guests={guests}
        onGuestsChange={setGuests}
        contactEmail={contactEmail}
        onContactEmailChange={setContactEmail}
        total={total}
        onBack={() => setStep('fares')}
        onContinue={() => setStep('seat')}
      />
      </>
    );
  }

  if (step === 'fares' && flight) {
    return (
      <>
      {newSearchBar}
      <FlightQuotePage
        flight={flight}
        returnFlight={returnFlight}
        connection={connection}
        paxCount={paxCount}
        preferredCabin={preferredCabin}
        onBack={() => setStep('search')}
        onChoose={(chosen) => {
          setChoice(chosen);
          setStep('guests');
        }}
      />
      </>
    );
  }

  return (
    <SearchPage
      onSelectFlight={(chosen, party, cabin, inbound, throughLegs) => {
        setFlight(chosen);
        setReturnFlight(inbound ?? null);
        setConnection(throughLegs ?? []);
        setTravellers(party);
        setPreferredCabin(cabin);
        setSeats([]);
        // Fresh forms sized to the declared party, in paxTypes order
        // (adults, then children, then infants).
        setGuests(Array.from({ length: totalTravellers(party) }, emptyPassenger));
        setBags(Array.from({ length: totalTravellers(party) }, () => 0));
        setContactEmail('');
        setStep('fares');
      }}
    />
  );
}

/**
 * List and detail for my bookings.
 *
 * <p>Detail is local state rather than a /bookings/:id route so returning to the
 * list does not re-fetch it - the list is already correct, and a route would
 * throw it away on every back-navigation.
 */
function BookingsRoute() {
  const [open, setOpen] = useState<Booking | null>(null);
  const location = useLocation();

  // Close the open detail whenever /bookings is navigated to - including
  // clicking "My trips" in the header while already here (same path, new
  // history key). Without this the detail is local state that a same-path
  // navigation can't clear, so the link appears to do nothing.
  useEffect(() => {
    setOpen(null);
  }, [location.key]);

  return open ? (
    <BookingDetailPage booking={open} onBack={() => setOpen(null)} />
  ) : (
    <MyBookingsPage onOpen={setOpen} />
  );
}

/**
 * The footer belongs under content pages, not the full-height auth split - a
 * marketing footer beneath a centred sign-in card just pushes it off the fold.
 */
function ChromeFooter() {
  const { pathname } = useLocation();
  const authPaths = ['/sign-in', '/register', '/forgot-password', '/reset-password'];
  return authPaths.includes(pathname) ? null : <SiteFooter />;
}

export default function App() {
  // Root locale subscription: a language/currency switch re-renders the whole
  // tree in place (no reload, no remount - journey state survives).
  useLocale();
  return (
    <BrowserRouter>
      <SessionBootstrap>
        <div className="flex min-h-full flex-col">
          <Header />
          <div className="flex-1">
            <RouteErrorBoundary>
            <Routes>
              <Route path="/sign-in" element={<SignInPage />} />
              <Route path="/register" element={<RegisterPage />} />
              <Route path="/forgot-password" element={<ForgotPasswordPage />} />
              <Route path="/reset-password" element={<ResetPasswordPage />} />
              {/* Landing (Module 2). */}
              <Route path="/" element={<LandingPage />} />
              {/* Public search + booking journey; the booking steps gate
                  themselves on the session (see BookingJourney). */}
              <Route path="/search" element={<BookingJourney />} />
              <Route
                path="/bookings"
                element={
                  <RequireSession>
                    <BookingsRoute />
                  </RequireSession>
                }
              />
              <Route
                path="/profile"
                element={
                  <RequireSession>
                    <ProfilePage />
                  </RequireSession>
                }
              />
              <Route
                path="/admin"
                element={
                  <RequireAdmin>
                    <AdminPage />
                  </RequireAdmin>
                }
              />
              <Route path="*" element={<ErrorPage code="404" />} />
            </Routes>
            </RouteErrorBoundary>
          </div>
          <ChromeFooter />
        </div>
      </SessionBootstrap>
    </BrowserRouter>
  );
}
