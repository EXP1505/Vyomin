import { useEffect, useMemo, useRef, useState } from 'react';
import { Panel } from '../components/design/Panel';
import { Sparkline } from '../components/design/Sparkline';
import { CountUp } from '../components/design/CountUp';
import { Globe } from '../components/globe/Globe';
import { LiveSignalFeed } from '../components/home/LiveSignalFeed';
import { MiniGraphPreview } from '../components/home/MiniGraphPreview';
import { useTelemetryStore } from '../store/telemetryStore';
import { useTelemetrySocket } from '../hooks/useTelemetrySocket';
import { useGraphStore } from '../store/graphStore';
import { useRecentConflicts } from '../hooks/useRecentConflicts';
import { useAuthStore } from '../store/authStore';
import { useBookmarkedStockSymbols } from '../store/bookmarkStore';
import { useNavigate } from 'react-router-dom';

const API_BASE = 'http://localhost:8080/api/intel';
const TICKER_POLL_MS = 20000;
const ALL_FLIGHT_TYPES = new Set(['MILITARY', 'CARGO', 'HELICOPTER', 'DRONE', 'PRIVATE', 'UNKNOWN']);
const MILITARY_ONLY = new Set(['MILITARY']);

function useTrendingTickers() {
  const token = useAuthStore((s) => s.token);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const bookmarkedSymbolsKey = useBookmarkedStockSymbols();
  const [tickers, setTickers] = useState([]);

  useEffect(() => {
    let cancelled = false;
    const headers = token ? { Authorization: `Bearer ${token}` } : {};
    const bookmarkedSymbols = bookmarkedSymbolsKey ? bookmarkedSymbolsKey.split(',') : [];

    const attachSeries = (rows) =>
      Promise.all(
        rows.map(async (q) => {
          try {
            const res = await fetch(`/api/intel/finance/candles?symbol=${encodeURIComponent(q.symbol)}`, { headers });
            const json = await res.json();
            const closes = json?.data?.c || json?.c || [];
            return { ...q, closes: closes.slice(-20) };
          } catch {
            return { ...q, closes: [] };
          }
        })
      );

    const load = async () => {
      try {
        let top;
        if (isAuthenticated && bookmarkedSymbols.length > 0) {
          const quotes = await Promise.all(
            bookmarkedSymbols.slice(0, 4).map(async (symbol) => {
              try {
                const res = await fetch(`/api/intel/finance/search?symbol=${encodeURIComponent(symbol)}`, { headers });
                const json = await res.json();
                return json?.data || null;
              } catch {
                return null;
              }
            })
          );
          top = quotes.filter(Boolean);
        } else {
          const res = await fetch('/api/intel/finance/trending', { headers });
          const payload = await res.json();
          top = (payload?.data || []).slice(0, 4);
        }
        if (cancelled) return;
        const withSeries = await attachSeries(top);
        if (!cancelled) setTickers(withSeries);
      } catch {
        // ignore
      }
    };

    load();
    // Polled (not pushed) so the Finance preview card's price-update flash has something to
    // react to - the trending endpoint has no live-update channel of its own.
    const id = setInterval(load, TICKER_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [token, isAuthenticated, bookmarkedSymbolsKey]);

  return tickers;
}

function useGraphCounts() {
  const nodes = useGraphStore((s) => s.nodes);
  const edges = useGraphStore((s) => s.edges);
  const [fallback, setFallback] = useState({ nodes: 0, edges: 0 });

  useEffect(() => {
    if (nodes.length || edges.length) return;
    fetch(`${API_BASE}/conflicts/count`)
      .then((r) => r.json())
      .then((data) => {
        const count = data?.count ?? 0;
        setFallback({ nodes: count, edges: Math.max(0, count - 1) });
      })
      .catch(() => {});
  }, [nodes.length, edges.length]);

  // Memoized on the primitive lengths (not object identity) - without this, Home's frequent
  // flights re-renders would hand MiniGraphPreview a new object every time, thrashing the
  // force-graph engine's render loop before any frame could persist to the canvas.
  return useMemo(
    () => (nodes.length || edges.length ? { nodes: nodes.length, edges: edges.length } : fallback),
    [nodes.length, edges.length, fallback]
  );
}

function StatsStrip({ trackCount, entityCount, marketCount }) {
  return (
    <Panel className="flex flex-wrap items-center justify-center gap-6 px-6 py-4">
      <span className="font-mono-data text-sm" style={{ color: 'var(--text)' }}>
        <CountUp value={trackCount} /> <span style={{ color: 'var(--text-faint)' }}>aircraft tracked</span>
      </span>
      <span style={{ color: 'var(--hairline)' }}>·</span>
      <span className="font-mono-data text-sm" style={{ color: 'var(--text)' }}>
        <CountUp value={entityCount} /> <span style={{ color: 'var(--text-faint)' }}>entities mapped</span>
      </span>
      <span style={{ color: 'var(--hairline)' }}>·</span>
      <span className="font-mono-data text-sm" style={{ color: 'var(--text)' }}>
        <CountUp value={marketCount} /> <span style={{ color: 'var(--text-faint)' }}>markets watched</span>
      </span>
    </Panel>
  );
}

function PreviewCardShell({ eyebrow, title, onClick, children }) {
  return (
    <Panel
      as="button"
      onClick={onClick}
      className="flex h-full flex-col overflow-hidden p-0 text-left transition-colors hover:border-[var(--accent)]"
    >
      <div className="px-5 pt-4 pb-1">
        <div className="text-[10px] uppercase tracking-[0.2em]" style={{ color: 'var(--text-faint)' }}>
          {eyebrow}
        </div>
        <div className="mt-1 text-base font-semibold" style={{ color: 'var(--text)' }}>
          {title}
        </div>
      </div>
      <div className="relative flex-1 overflow-hidden">{children}</div>
    </Panel>
  );
}

function RadarPreviewCard({ onNavigate, flights }) {
  return (
    <PreviewCardShell eyebrow="/ RADAR TELEMETRY" title={`${flights.length} live tracks`} onClick={() => onNavigate('radar')}>
      <div className="absolute inset-0">
        <Globe compact flights={flights} activeTypes={ALL_FLIGHT_TYPES} />
      </div>
    </PreviewCardShell>
  );
}

function GraphPreviewCard({ onNavigate, conflicts, graphCounts }) {
  return (
    <PreviewCardShell
      eyebrow="/ INTELLIGENCE GRAPH"
      title={`${graphCounts.nodes} entities mapped`}
      onClick={() => onNavigate('graph')}
    >
      <div className="flex h-full items-center justify-center">
        <MiniGraphPreview conflicts={conflicts} width={260} height={140} />
      </div>
    </PreviewCardShell>
  );
}

function FinancePreviewCard({ onNavigate, tickers }) {
  const [flash, setFlash] = useState(null);
  const prevPrices = useRef(new Map());

  useEffect(() => {
    for (const t of tickers) {
      const prev = prevPrices.current.get(t.symbol);
      prevPrices.current.set(t.symbol, t.currentPrice);
      if (prev !== undefined && prev !== t.currentPrice) {
        setFlash({ symbol: t.symbol, positive: t.currentPrice >= prev });
        const id = setTimeout(() => setFlash(null), 800);
        return () => clearTimeout(id);
      }
    }
  }, [tickers]);

  return (
    <PreviewCardShell
      eyebrow="/ FINANCIAL TELEMETRY"
      title={`${tickers.length} markets watched`}
      onClick={() => onNavigate('finance')}
    >
      <div className="flex h-full flex-col gap-2 px-5 py-3">
        {tickers.length === 0 && (
          <div className="text-xs" style={{ color: 'var(--text-faint)' }}>
            Loading quotes…
          </div>
        )}
        {tickers.map((t) => {
          const flashing = flash?.symbol === t.symbol;
          const flashColor = flash?.positive ? 'var(--positive)' : 'var(--negative)';
          return (
            <div
              key={t.symbol}
              className="flex items-center justify-between gap-2 border px-2 py-1 transition-colors duration-500"
              style={{ borderColor: flashing ? flashColor : 'transparent' }}
            >
              <span className="font-mono-data text-xs" style={{ color: 'var(--text-dim)' }}>
                {t.symbol}
              </span>
              <Sparkline values={t.closes} width={70} height={20} />
            </div>
          );
        })}
      </div>
    </PreviewCardShell>
  );
}

export function Home() {
  const navigate = useNavigate();
  const onNavigate = (key) => navigate('/' + key);
  useTelemetrySocket();
  const flights = useTelemetryStore((s) => s.flights);
  const graphCounts = useGraphCounts();
  const tickers = useTrendingTickers();
  const recentConflicts = useRecentConflicts();

  return (
    <div className="flex h-full flex-col gap-5 overflow-y-auto pr-1">
      <div>
        <h1 className="text-3xl font-semibold tracking-tight" style={{ color: 'var(--text)' }}>
          Where geopolitics moves, markets follow.
        </h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-dim)' }}>
          Live military telemetry, global conflict signals, and market reaction — cross-referenced in real time.
        </p>
      </div>

      <div className="grid gap-5 lg:grid-cols-[2fr_1fr]" style={{ height: '54vh', minHeight: '420px' }}>
        <Panel className="relative overflow-hidden p-0">
          <Globe flights={flights} activeTypes={MILITARY_ONLY} conflicts={recentConflicts} disableTouchZoom />
        </Panel>
        <Panel className="flex flex-col overflow-hidden p-4">
          <div className="mb-2 text-[10px] uppercase tracking-[0.2em]" style={{ color: 'var(--text-faint)' }}>
            / LIVE SIGNAL FEED
          </div>
          <LiveSignalFeed conflicts={recentConflicts} tickers={tickers} flights={flights} />
        </Panel>
      </div>

      <StatsStrip trackCount={flights.length} entityCount={graphCounts.nodes} marketCount={tickers.length} />

      <div className="grid grid-cols-1 gap-5 md:grid-cols-3" style={{ height: '220px' }}>
        <RadarPreviewCard onNavigate={onNavigate} flights={flights} />
        <GraphPreviewCard onNavigate={onNavigate} conflicts={recentConflicts} graphCounts={graphCounts} />
        <FinancePreviewCard onNavigate={onNavigate} tickers={tickers} />
      </div>
    </div>
  );
}
