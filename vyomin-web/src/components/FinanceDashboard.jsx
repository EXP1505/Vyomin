import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '../store/authStore';
import StockDetailModal from './StockDetailModal';
import { Panel } from './design/Panel';
import { Sparkline } from './design/Sparkline';

function TickerCard({ q, onSelect }) {
  const positive = typeof q.percentChange === 'number' && q.percentChange >= 0;
  const color = positive ? 'var(--positive)' : 'var(--negative)';
  const formatPrice = (n) => (typeof n === 'number' && !Number.isNaN(n) ? n.toFixed(2) : '—');
  const formatPercent = (n) => {
    if (typeof n !== 'number' || Number.isNaN(n)) return '—';
    const sign = n > 0 ? '+' : '';
    return `${sign}${n.toFixed(2)}%`;
  };

  return (
    <Panel as="button" onClick={() => onSelect(q.symbol)} className="p-4 text-left transition-colors hover:border-[var(--accent)]">
      <div className="flex items-start justify-between gap-2">
        <div>
          <div className="font-mono-data text-sm font-semibold" style={{ color: 'var(--text)' }}>{q.symbol}</div>
          <div className="font-mono-data text-xs mt-0.5" style={{ color: 'var(--text-dim)' }}>${formatPrice(q.currentPrice)}</div>
        </div>
        <span className="font-mono-data text-xs px-2 py-1 border" style={{ color, borderColor: color, background: `${color}18` }}>
          {formatPercent(q.percentChange)}
        </span>
      </div>
      <div className="mt-3">
        <Sparkline values={q.closes || []} width={140} height={28} />
      </div>
    </Panel>
  );
}

export const FinanceDashboard = ({ fullPage = false, onJumpToGraph }) => {
  const token = useAuthStore((s) => s.token);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [results, setResults] = useState([]);
  const [selectedSymbol, setSelectedSymbol] = useState(null);

  const authHeaders = useMemo(() => (token ? { Authorization: `Bearer ${token}` } : {}), [token]);

  const attachSparkline = async (rows) => {
    const withSeries = await Promise.all(
      rows.map(async (q) => {
        try {
          const res = await fetch(`/api/intel/finance/candles?symbol=${encodeURIComponent(q.symbol)}`, {
            headers: authHeaders,
          });
          const json = await res.json();
          const closes = json?.data?.c || [];
          return { ...q, closes: closes.slice(-20) };
        } catch {
          return { ...q, closes: [] };
        }
      })
    );
    return withSeries;
  };

  const fetchTrending = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/api/intel/finance/trending', {
        headers: { 'Content-Type': 'application/json', ...authHeaders },
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body?.error || `Failed to fetch trending (${res.status})`);
      }

      const payload = await res.json();
      const data = payload?.data || [];
      const rows = data.map((q) => ({
        symbol: q.symbol,
        currentPrice: q.currentPrice,
        percentChange: q.percentChange,
      }));
      setResults(rows);
      setResults(await attachSparkline(rows));
    } catch (e) {
      setError(e.message || 'Failed to fetch trending');
    } finally {
      setLoading(false);
    }
  };

  const submitSearch = async (e) => {
    e.preventDefault();
    const symbol = query.trim().toUpperCase();
    if (!symbol) return;

    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/intel/finance/search?symbol=${encodeURIComponent(symbol)}`, {
        headers: { 'Content-Type': 'application/json', ...authHeaders },
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body?.error || `Failed to fetch quote (${res.status})`);
      }

      const payload = await res.json();
      const q = payload?.data;
      const rows = q ? [{ symbol: q.symbol, currentPrice: q.currentPrice, percentChange: q.percentChange }] : [];
      setResults(await attachSparkline(rows));
    } catch (e) {
      setError(e.message || 'Failed to search symbol');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchTrending();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      <Panel
        as="aside"
        className={fullPage ? 'flex h-full w-full flex-col' : 'flex h-full w-[420px] max-w-[85vw] flex-col'}
      >
        <div className="p-5 border-b" style={{ borderColor: 'var(--hairline)' }}>
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold" style={{ color: 'var(--text)' }}>Financial Telemetry</h2>
            <div className="font-mono-data text-xs" style={{ color: 'var(--text-faint)' }}>FINNHUB QUOTES</div>
          </div>

          <form onSubmit={submitSearch} className="mt-4 flex gap-2">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search ticker (e.g., AAPL)"
              className="font-mono-data flex-1 border px-3 py-2 text-sm focus:outline-none transition-colors"
              style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)', color: 'var(--text)' }}
              onFocus={(e) => (e.target.style.borderColor = 'var(--accent)')}
              onBlur={(e) => (e.target.style.borderColor = 'var(--hairline)')}
            />
            <button
              type="submit"
              className="font-mono-data font-semibold px-4 py-2 border transition-colors"
              style={{ borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }}
            >
              Search
            </button>
          </form>
        </div>

        <div className="p-5 flex-1 overflow-auto">
          {loading && results.length === 0 && (
            <div className="text-sm" style={{ color: 'var(--text-dim)' }}>Loading quotes...</div>
          )}
          {error && (
            <div className="mb-4 p-3 border text-sm" style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}>
              {error}
            </div>
          )}

          <div className={fullPage ? 'grid grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-3' : 'grid grid-cols-1 gap-3'}>
            {results.map((q) => (
              <TickerCard key={q.symbol} q={q} onSelect={setSelectedSymbol} />
            ))}

            {!loading && results.length === 0 && !error && (
              <div className="text-sm" style={{ color: 'var(--text-faint)' }}>No quote data.</div>
            )}
          </div>
        </div>
      </Panel>

      {selectedSymbol ? (
        <StockDetailModal symbol={selectedSymbol} onClose={() => setSelectedSymbol(null)} onJumpToGraph={onJumpToGraph} />
      ) : null}
    </>
  );
};
