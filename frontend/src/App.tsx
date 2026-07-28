import { useEffect, useState } from 'react';
import {
  BrowserRouter,
  Link,
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
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
import { CheckoutPage } from './features/booking/CheckoutPage';
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
              className="rounded-lg px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
            >
              Search flights
            </Link>
          )}
          {signedIn ? (
            <>
              {!isAdmin ? (
                <Link
                  to="/bookings"
                  className="rounded-lg px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
                >
                  My trips
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
                Sign out
              </button>
            </>
          ) : (
            <Link
              to="/sign-in"
              className="rounded-lg bg-white/10 px-3.5 py-1.5 font-semibold text-white ring-1 ring-white/20 transition hover:bg-white/20"
            >
              Log in
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
type Step = 'search' | 'fares' | 'seat' | 'checkout' | 'confirmed';

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
  // Kept in local state rather than routes: these are steps within one search,
  // and a /flights/:id route would re-fetch (and lose the results behind it) on
  // every back-navigation.
  const [step, setStep] = useState<Step>('search');
  const [flight, setFlight] = useState<Flight | null>(null);
  const [travellers, setTravellers] = useState<Travellers>(ONE_ADULT);
  const [choice, setChoice] = useState<FareChoice | null>(null);
  // One chosen seat per traveller, in passenger order; shorter than the party
  // means the rest are auto-assigned free at check-in.
  const [seats, setSeats] = useState<AircraftSeat[]>([]);
  const [result, setResult] = useState<{ booking: Booking; payment: Payment } | null>(null);

  const paxCount = totalTravellers(travellers);
  // Adults first, then children, then infants - the order checkout renders
  // its forms in, so each one is labelled and DOB-bounded for the right type.
  const paxTypes: PassengerType[] = [
    ...Array.from({ length: travellers.adults }, () => 'ADULT' as const),
    ...Array.from({ length: travellers.children }, () => 'CHILD' as const),
    ...Array.from({ length: travellers.infants }, () => 'INFANT' as const),
  ];

  function restart() {
    setStep('search');
    setFlight(null);
    setTravellers(ONE_ADULT);
    setChoice(null);
    setSeats([]);
    setResult(null);
  }

  // Search and fares are public; everything from seat selection on writes owned
  // data and needs a principal. An anonymous browser that reaches those steps
  // meets the log-in wall here - with its flight and fare still in state, so it
  // resumes intact the instant the session appears.
  if ((step === 'seat' || step === 'checkout' || step === 'confirmed') && !signedIn) {
    return <BookingAuthGate flight={flight} choice={choice} onBack={() => setStep('fares')} />;
  }

  if (step === 'confirmed' && result) {
    return (
      <ConfirmationPage booking={result.booking} payment={result.payment} onDone={restart} />
    );
  }

  if (step === 'checkout' && flight && choice) {
    return (
      <CheckoutPage
        flight={flight}
        cabin={choice.cabin}
        fare={choice.fare}
        baseFare={choice.baseFare}
        currency={choice.currency}
        seats={seats}
        paxTypes={paxTypes}
        onBack={() => setStep('seat')}
        onBooked={(booking, payment) => {
          setResult({ booking, payment });
          setStep('confirmed');
        }}
      />
    );
  }

  if (step === 'seat' && flight && choice) {
    return (
      <SeatSelectionPage
        flight={flight}
        cabin={choice.cabin}
        fare={choice.fare}
        baseFare={choice.baseFare}
        currency={choice.currency}
        paxCount={paxCount}
        onBack={() => setStep('fares')}
        onContinue={(chosen) => {
          setSeats(chosen);
          setStep('checkout');
        }}
      />
    );
  }

  if (step === 'fares' && flight) {
    return (
      <FlightQuotePage
        flight={flight}
        onBack={() => setStep('search')}
        onChoose={(chosen) => {
          setChoice(chosen);
          setStep('seat');
        }}
      />
    );
  }

  return (
    <SearchPage
      onSelectFlight={(chosen, party) => {
        setFlight(chosen);
        setTravellers(party);
        setSeats([]);
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
