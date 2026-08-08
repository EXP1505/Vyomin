import { useState } from 'react';
import { Background } from './components/design/Background';
import { TopBar } from './components/design/TopBar';
import { Home } from './pages/Home';
import { RadarView } from './components/RadarView';
import { FinanceDashboard } from './components/FinanceDashboard';
import IntelligenceGraphExplorer from './pages/IntelligenceGraphExplorer';
import { useTelemetryStore } from './store/telemetryStore';

const NAV_ITEMS = [
  { key: 'home', label: 'Home' },
  { key: 'radar', label: 'Radar Telemetry' },
  { key: 'graph', label: 'Intelligence Graph' },
  { key: 'finance', label: 'Financial Telemetry' },
];

function App() {
  const [activeTab, setActiveTab] = useState('home');
  const [graphSeed, setGraphSeed] = useState(null);
  const flights = useTelemetryStore((s) => s.flights);

  const jumpToGraph = (query, type = 'conflict') => {
    setGraphSeed({ query, type, ts: Date.now() });
    setActiveTab('graph');
  };

  return (
    <div className="relative flex h-screen flex-col text-[var(--text)] overflow-hidden">
      <Background />

      <div className="relative z-10 flex flex-grow flex-col overflow-hidden">
        <TopBar navItems={NAV_ITEMS} activeTab={activeTab} onNavigate={setActiveTab} statusLabel="LIVE" statusOk>
          <span>{flights.length} TRACKS</span>
        </TopBar>
        <main className={`flex-grow overflow-hidden ${activeTab === 'graph' ? '' : 'p-6'}`}>
          {activeTab === 'home' && <Home onNavigate={setActiveTab} />}
          {activeTab === 'radar' && <RadarView />}
          {activeTab === 'graph' && <IntelligenceGraphExplorer initialSearch={graphSeed} />}
          {activeTab === 'finance' && <FinanceDashboard fullPage onJumpToGraph={jumpToGraph} />}
        </main>
      </div>
    </div>
  );
}

export default App;
