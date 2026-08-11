import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Background } from '../components/design/Background';
import { TopBar } from '../components/design/TopBar';
import { AccountControl } from '../components/AccountControl';
import { RequireAuth } from '../components/RequireAuth';
import { useTelemetryStore } from '../store/telemetryStore';

const NAV_ITEMS = [
  { key: 'home', label: 'Home' },
  { key: 'radar', label: 'Radar Telemetry' },
  { key: 'graph', label: 'Intelligence Graph' },
  { key: 'finance', label: 'Financial Telemetry' },
];

export function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const flights = useTelemetryStore((s) => s.flights);
  const activeTab = location.pathname.slice(1) || 'home';

  return (
    <RequireAuth>
      <div className="relative flex h-screen flex-col text-[var(--text)] overflow-hidden">
        <Background />

        <div className="relative z-10 flex flex-grow flex-col overflow-hidden">
          <TopBar navItems={NAV_ITEMS} activeTab={activeTab} onNavigate={(key) => navigate('/' + key)} statusLabel="LIVE" statusOk>
            <span>{flights.length} TRACKS</span>
            <AccountControl />
          </TopBar>
          <main className={`flex-grow overflow-hidden ${activeTab === 'graph' ? '' : 'p-6'}`}>
            <Outlet />
          </main>
        </div>
      </div>
    </RequireAuth>
  );
}
