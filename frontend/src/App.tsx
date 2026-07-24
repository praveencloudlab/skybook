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
import { Button } from './components/Button';
import { useSession } from './features/auth/useSession';
import { RegisterPage } from './features/auth/RegisterPage';
import { SignInPage } from './features/auth/SignInPage';
import { SearchPage } from './features/search/SearchPage';
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
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-3">
        <Link to="/" className="text-sm font-semibold tracking-widest text-brand-700 uppercase">
          SkyBook
        </Link>
        {signedIn ? (
          <div className="flex items-center gap-3 text-sm">
            <span className="hidden text-slate-600 sm:inline">{subject}</span>
            <Button variant="secondary" onClick={signOut}>
              Sign out
            </Button>
          </div>
        ) : (
          <Link to="/sign-in" className="text-sm font-medium text-brand-700 hover:underline">
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

function HomePage() {
  return (
    <SearchPage
      onSelectFlight={() => {
        // Quote + seat selection land in build-order steps 7-8; until then the
        // card's Select button is intentionally inert rather than pretending.
      }}
    />
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
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </SessionBootstrap>
    </BrowserRouter>
  );
}
