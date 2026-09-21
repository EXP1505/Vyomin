import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Background } from '../components/design/Background';
import { Logo } from '../components/design/Logo';
import { Panel } from '../components/design/Panel';
import { AuthPanel } from '../components/AuthPanel';
import { useAuthStore } from '../store/authStore';
import { useBookmarkStore } from '../store/bookmarkStore';

// Mirrors AppLayout.jsx's NAV_ITEMS exactly - Radar Telemetry is intentionally omitted there
// (OpenSky blocks Render's IP ranges) so it must not be promised here either.
const FEATURES = [
  {
    tag: 'LIVE GLOBE',
    title: 'Command Globe',
    body: 'A live 3D globe of the world\'s conflict signals as they\'re ingested, with a real-time feed alongside it.',
  },
  {
    tag: 'GDELT-BACKED',
    title: 'Intelligence Graph',
    body: 'Search a country, conflict, or company and see how it connects to everything else - actors, sectors, sanctions - as a live graph.',
  },
  {
    tag: 'MARKET DATA',
    title: 'Financial Telemetry',
    body: 'Quotes, movers, and news for any ticker, cross-referenced against the conflicts and countries actually driving them.',
  },
  {
    tag: 'STATISTICAL',
    title: 'Event Study Analyzer',
    body: 'Tests whether a class of geopolitical event (Fight, Protest, Coerce, ...) actually moves a stock basket - bootstrap significance testing with Bonferroni correction, not just a chart overlay.',
  },
];

const DATA_NOTES = [
  'GDELT 2.0 event feed, ingested continuously',
  'Statistical tests corrected for multiple comparisons',
  'Star anything - stocks, countries, conflicts - to build a personal watchlist',
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
    // body has overflow:hidden globally (the authenticated app is a fixed-viewport layout that
    // manages its own internal scroll areas) - this page must be its own scroll container rather
    // than relying on the body to scroll, since it's taller than one viewport with 4 feature cards.
    <div className="relative flex h-screen flex-col overflow-y-auto text-[var(--text)]">
      <Background />

      <div className="relative z-10 flex flex-grow flex-col">
        <header className="flex h-14 shrink-0 items-center px-5 border-b" style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}>
          <Logo size={36} />
          <span className="ml-3 text-sm font-semibold tracking-[0.2em]" style={{ color: 'var(--text)' }}>VYOMIN</span>
        </header>

        <main className="flex-grow px-6 py-12 lg:py-20">
          <div className="mx-auto flex max-w-6xl flex-col gap-16 lg:flex-row lg:items-start lg:justify-between">
            <div className="max-w-xl">
              <div
                className="inline-flex items-center gap-2 border px-3 py-1 text-xs font-mono-data tracking-wide"
                style={{ borderColor: 'var(--hairline)', color: 'var(--text-faint)', background: 'var(--panel-2)' }}
              >
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--positive)' }} />
                LIVE GEOPOLITICAL &amp; MARKET DATA
              </div>

              <h1 className="mt-5 text-4xl font-semibold tracking-tight lg:text-5xl" style={{ color: 'var(--text)' }}>
                Where geopolitics moves, markets follow.
              </h1>
              <p className="mt-4 text-base" style={{ color: 'var(--text-dim)' }}>
                Vyomin cross-references a live global conflict-event feed against market reaction in
                one command center - so you can see how the world's events ripple into the assets
                you're watching, backed by real statistical testing instead of a chart overlay that
                just looks convincing.
              </p>

              <div className="mt-10 grid grid-cols-1 gap-4 sm:grid-cols-2">
                {FEATURES.map((f) => (
                  <Panel key={f.title} className="p-4">
                    <span
                      className="font-mono-data text-[0.65rem] tracking-widest"
                      style={{ color: 'var(--text-faint)' }}
                    >
                      {f.tag}
                    </span>
                    <h3 className="mt-1 text-sm font-semibold" style={{ color: 'var(--accent)' }}>{f.title}</h3>
                    <p className="mt-1.5 text-sm" style={{ color: 'var(--text-dim)' }}>{f.body}</p>
                  </Panel>
                ))}
              </div>

              <ul className="mt-8 flex flex-col gap-1.5">
                {DATA_NOTES.map((note) => (
                  <li key={note} className="flex items-center gap-2 text-xs" style={{ color: 'var(--text-faint)' }}>
                    <span className="h-1 w-1 shrink-0 rounded-full" style={{ background: 'var(--accent)' }} />
                    {note}
                  </li>
                ))}
              </ul>

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
