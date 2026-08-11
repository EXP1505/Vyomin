import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Background } from '../components/design/Background';
import { Panel } from '../components/design/Panel';
import { AuthPanel } from '../components/AuthPanel';
import { useAuthStore } from '../store/authStore';
import { useBookmarkStore } from '../store/bookmarkStore';

const FEATURES = [
  {
    title: 'Radar Telemetry',
    body: 'Live aircraft tracks over a 3D globe, sourced from real flight telemetry - military, cargo, and civilian traffic as it moves.',
  },
  {
    title: 'Intelligence Graph',
    body: 'Explore how countries, conflicts, companies, and sectors connect to each other, built from live geopolitical event data.',
  },
  {
    title: 'Financial Telemetry',
    body: 'Market quotes, movers, and news cross-referenced against the conflicts and countries driving them.',
  },
  {
    title: 'Bookmarks & Watchlists',
    body: 'Star any stock, country, or conflict to keep it in your personal watchlist and jump back to it anytime.',
  },
];

export function LandingPage() {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const login = useAuthStore((s) => s.login);
  const fetchBookmarks = useBookmarkStore((s) => s.fetchAll);

  useEffect(() => {
    if (isAuthenticated) navigate('/home', { replace: true });
  }, [isAuthenticated, navigate]);

  const handleAuthenticated = (data) => {
    login({ token: data.token, email: data.email, name: data.name, pictureUrl: data.pictureUrl });
    fetchBookmarks();
    navigate('/home', { replace: true });
  };

  if (isAuthenticated) return null;

  return (
    <div className="relative flex min-h-screen flex-col text-[var(--text)] overflow-hidden">
      <Background />

      <div className="relative z-10 flex flex-grow flex-col">
        <header className="flex h-14 shrink-0 items-center px-5 border-b" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}>
          <span
            className="inline-block h-2.5 w-2.5 rotate-45"
            style={{ background: 'var(--accent)', animation: 'vyomin-pulse 2.4s ease-in-out infinite' }}
          />
          <span className="ml-3 text-sm font-semibold tracking-[0.2em]" style={{ color: 'var(--text)' }}>VYOMIN</span>
        </header>

        <main className="flex-grow px-6 py-12 lg:py-20">
          <div className="mx-auto flex max-w-6xl flex-col gap-16 lg:flex-row lg:items-start lg:justify-between">
            <div className="max-w-xl">
              <h1 className="text-4xl font-semibold tracking-tight lg:text-5xl" style={{ color: 'var(--text)' }}>
                Where geopolitics moves, markets follow.
              </h1>
              <p className="mt-4 text-base" style={{ color: 'var(--text-dim)' }}>
                Vyomin cross-references live military telemetry, global conflict signals, and market
                reaction in one command center - so you can see how the world's events ripple into the
                assets you're watching, in real time.
              </p>

              <div className="mt-10 grid grid-cols-1 gap-4 sm:grid-cols-2">
                {FEATURES.map((f) => (
                  <Panel key={f.title} className="p-4">
                    <h3 className="text-sm font-semibold" style={{ color: 'var(--accent)' }}>{f.title}</h3>
                    <p className="mt-1.5 text-sm" style={{ color: 'var(--text-dim)' }}>{f.body}</p>
                  </Panel>
                ))}
              </div>

              <p className="mt-8 text-xs" style={{ color: 'var(--text-faint)' }}>
                Log in or create an account to enter the command center - every feature above requires
                an account so your bookmarks and watchlists follow you across sessions.
              </p>
            </div>

            <div className="flex justify-center lg:justify-start">
              <AuthPanel onSuccess={handleAuthenticated} />
            </div>
          </div>
        </main>
      </div>

      <style>{`
        @keyframes vyomin-pulse {
          0%, 100% { opacity: 1; transform: rotate(45deg) scale(1); }
          50% { opacity: 0.45; transform: rotate(45deg) scale(0.85); }
        }
      `}</style>
    </div>
  );
}
