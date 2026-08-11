import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { LandingPage } from './pages/LandingPage';
import { Home } from './pages/Home';
import { RadarView } from './components/RadarView';
import { FinanceDashboard } from './components/FinanceDashboard';
import IntelligenceGraphExplorer from './pages/IntelligenceGraphExplorer';
import { AppLayout } from './layouts/AppLayout';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route element={<AppLayout />}>
          <Route path="/home" element={<Home />} />
          <Route path="/radar" element={<RadarView />} />
          <Route path="/graph" element={<IntelligenceGraphExplorer />} />
          <Route path="/finance" element={<FinanceDashboard fullPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
