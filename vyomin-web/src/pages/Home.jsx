import { useEffect, useState } from 'react';
import { Panel } from '../components/design/Panel';
import { Sparkline } from '../components/design/Sparkline';
import { Globe } from '../components/globe/Globe';
import { useTelemetryStore } from '../store/telemetryStore';
import { useGraphStore } from '../store/graphStore';
import { useAuthStore } from '../store/authStore';

const API_BASE = 'http://localhost:8080/api/intel';

function useTrendingTickers() {
  const token = useAuthStore((s) => s.token);
  const [tickers, setTickers] = useState([]);

  useEffect(() => {
    let cancelled = false;
    const headers = token ? { Authorization: `Bearer ${token}` } : {};

    fetch('/api/intel/finance/trending', { headers })
      .then((r) => r.json())
      .then(async (payload) => {
        if (cancelled) return;
        const top = (payload?.data || []).slice(0, 4);
        const withSeries = await Promise.all(
          top.map(async (q) => {
            try {
              const res = await fetch(`/api/intel/finance/candles?symbol=${encodeURIComponent(q.symbol)}`, { headers });
              const json = await res.json();
              const closes = json?.data?.c || json?.c || [];
              return { ...q, closes };
            } catch {
              return { ...q, closes: [] };
            }
          })
        );
        if (!cancelled) setTickers(withSeries);
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [token]);

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

  return nodes.length || edges.length ? { nodes: nodes.length, edges: edges.length } : fallback;
}

function NavTile({ eyebrow, title, description, onClick, children }) {
  return (
    <Panel
      as="button"
      onClick={onClick}
      className="flex h-full flex-col p-6 text-left transition-colors hover:border-[var(--accent)]"
    >
      <div className="text-[10px] uppercase tracking-[0.2em]" style={{ color: 'var(--text-faint)' }}>
        {eyebrow}
      </div>
      <div className="mt-1 text-xl font-semibold" style={{ color: 'var(--text)' }}>
        {title}
      </div>
      <p className="mt-2 text-xs" style={{ color: 'var(--text-dim)' }}>
        {description}
      </p>
      <div className="mt-auto flex flex-grow items-center justify-center pt-4">{children}</div>
    </Panel>
  );
}

export function Home({ onNavigate }) {
  const flights = useTelemetryStore((s) => s.flights);
  const graphCounts = useGraphCounts();
  const tickers = useTrendingTickers();

  return (
    <div className="grid h-full grid-cols-1 gap-5 md:grid-cols-3">
      <NavTile
        eyebrow="/ RADAR TELEMETRY"
        title="Global Track View"
        description={`${flights.length} live tracks across the 3D globe`}
        onClick={() => onNavigate('radar')}
      >
        <div className="h-40 w-40">
          <Globe compact />
        </div>
      </NavTile>

      <NavTile
        eyebrow="/ INTELLIGENCE GRAPH"
        title="Entity Network"
        description="Companies, conflicts, investors, and sectors cross-referenced"
        onClick={() => onNavigate('graph')}
      >
        <div className="font-mono-data text-center">
          <div className="text-3xl" style={{ color: 'var(--accent)' }}>{graphCounts.nodes}</div>
          <div className="text-[10px] tracking-widest" style={{ color: 'var(--text-faint)' }}>ENTITIES</div>
          <div className="mt-2 text-lg" style={{ color: 'var(--text-dim)' }}>{graphCounts.edges}</div>
          <div className="text-[10px] tracking-widest" style={{ color: 'var(--text-faint)' }}>RELATIONSHIPS</div>
        </div>
      </NavTile>

      <NavTile
        eyebrow="/ FINANCIAL TELEMETRY"
        title="Market Watch"
        description="Trending tickers with conflict-linked risk exposure"
        onClick={() => onNavigate('finance')}
      >
        <div className="flex w-full flex-col gap-2">
          {tickers.length === 0 && (
            <div className="text-center text-xs" style={{ color: 'var(--text-faint)' }}>
              Loading quotes…
            </div>
          )}
          {tickers.map((t) => (
            <div key={t.symbol} className="flex items-center justify-between gap-2">
              <span className="font-mono-data text-xs" style={{ color: 'var(--text-dim)' }}>{t.symbol}</span>
              <Sparkline values={t.closes} width={70} height={20} />
            </div>
          ))}
        </div>
      </NavTile>
    </div>
  );
}
