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
import { SearchPage } from './features/search/SearchPage';
import { FlightQuotePage } from './features/search/FlightQuotePage';
import { SeatSelectionPage } from './features/seats/SeatSelectionPage';
import { CheckoutPage } from './features/booking/CheckoutPage';
import { ConfirmationPage } from './features/booking/ConfirmationPage';
import { MyBookingsPage } from './features/bookings/MyBookingsPage';
import { BookingDetailPage } from './features/bookings/BookingDetailPage';
import type { AircraftSeat } from './api/seats';
import type { Booking } from './api/bookings';
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
  const { signedIn, subject } = useSession();
  const navigate = useNavigate();

  async function signOut() {
    // Must be a server call: the cookie is httpOnly, so the browser cannot
    // delete it itself.
    await authApi.logout();
    navigate('/sign-in', { replace: true });
  }

  return (
    <header className="sticky top-0 z-20 border-b border-brand-900/10 bg-brand-900 text-white shadow-sm">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-3">
        <Link to="/" className="group flex items-center gap-2.5">
          {/* A mark, not just a wordmark - it is what makes the header read as
              an airline rather than an admin console. */}
          <span className="grid h-7 w-7 place-items-center rounded bg-white/10 ring-1 ring-white/20 transition group-hover:bg-white/20">
            <svg viewBox="0 0 24 24" className="h-4 w-4 fill-white" aria-hidden="true">
              <path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5z" />
            </svg>
          </span>
          <span className="text-sm font-semibold tracking-[0.2em] uppercase">SkyBook</span>
        </Link>
        {signedIn ? (
          <div className="flex items-center gap-1 text-sm sm:gap-3">
            <Link
              to="/"
              className="rounded px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
            >
              Book
            </Link>
            <Link
              to="/bookings"
              className="rounded px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
            >
              My bookings
            </Link>
            <span className="hidden max-w-[16ch] truncate text-white/50 sm:inline" title={subject ?? ''}>
              {subject}
            </span>
            <button
              type="button"
              onClick={signOut}
              className="rounded px-2.5 py-1.5 font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
            >
              Sign out
            </button>
          </div>
        ) : (
          <Link
            to="/sign-in"
            className="rounded px-2.5 py-1.5 text-sm font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
          >
            Sign in
          </Link>
        )}
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

/** What the passenger has chosen so far, as they move through the journey. */
interface FareChoice {
  cabin: TravelClass;
  fare: FareType;
  baseFare: number;
  currency: string;
}

/** Where the passenger is in the booking journey. */
type Step = 'search' | 'fares' | 'seat' | 'checkout' | 'confirmed';

function HomePage() {
  // Kept in local state rather than routes: these are steps within one search,
  // and a /flights/:id route would re-fetch (and lose the results behind it) on
  // every back-navigation.
  const [step, setStep] = useState<Step>('search');
  const [flight, setFlight] = useState<Flight | null>(null);
  const [choice, setChoice] = useState<FareChoice | null>(null);
  const [seat, setSeat] = useState<AircraftSeat | null>(null);
  const [result, setResult] = useState<{ booking: Booking; payment: Payment } | null>(null);

  function restart() {
    setStep('search');
    setFlight(null);
    setChoice(null);
    setSeat(null);
    setResult(null);
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
        seat={seat}
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
        onBack={() => setStep('fares')}
        onContinue={(chosen) => {
          setSeat(chosen);
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
      onSelectFlight={(chosen) => {
        setFlight(chosen);
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

  return open ? (
    <BookingDetailPage booking={open} onBack={() => setOpen(null)} />
  ) : (
    <MyBookingsPage onOpen={setOpen} />
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <SessionBootstrap>
        <div className="min-h-full">
          <Header />
          <Routes>
            <Route path="/sign-in" element={<SignInPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route
              path="/"
              element={
                <RequireSession>
                  <HomePage />
                </RequireSession>
              }
            />
            <Route
              path="/bookings"
              element={
                <RequireSession>
                  <BookingsRoute />
                </RequireSession>
              }
            />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </SessionBootstrap>
    </BrowserRouter>
  );
}
