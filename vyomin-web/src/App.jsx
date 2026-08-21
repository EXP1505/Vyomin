import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { LandingPage } from './pages/LandingPage';
import { Home } from './pages/Home';
import { RadarView } from './components/RadarView';
import { FinanceDashboard } from './components/FinanceDashboard';
import IntelligenceGraphExplorer from './pages/IntelligenceGraphExplorer';
import EventStudyAnalysis from './pages/EventStudyAnalysis';
import CountryDossier from './pages/CountryDossier';
import { AppLayout } from './layouts/AppLayout';
import { CursorTrail } from './components/design/CursorTrail';

function App() {
  return (
    <BrowserRouter>
      <CursorTrail />
      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route element={<AppLayout />}>
          <Route path="/home" element={<Home />} />
          <Route path="/radar" element={<RadarView />} />
          <Route path="/graph" element={<IntelligenceGraphExplorer />} />
          <Route path="/finance" element={<FinanceDashboard fullPage />} />
          <Route path="/analysis" element={<EventStudyAnalysis />} />
          <Route path="/dossier/:countryCode" element={<CountryDossier />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
