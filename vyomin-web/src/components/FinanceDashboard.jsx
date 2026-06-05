import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '../store/authStore';

export const FinanceDashboard = ({ fullPage = false }) => {
  const token = useAuthStore((s) => s.token);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [results, setResults] = useState([]);

  const authHeaders = useMemo(() => {
    return token ? { Authorization: `Bearer ${token}` } : {};
  }, [token]);

  const fetchTrending = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/api/intel/finance/trending', {
        headers: {
          'Content-Type': 'application/json',
          ...authHeaders,
        },
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body?.error || `Failed to fetch trending (${res.status})`);
      }

      const payload = await res.json();
      const data = payload?.data || [];
      setResults(
        data.map((q) => ({
          symbol: q.symbol,
          currentPrice: q.currentPrice,
          percentChange: q.percentChange,
        }))
      );
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
        headers: {
          'Content-Type': 'application/json',
          ...authHeaders,
        },
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body?.error || `Failed to fetch quote (${res.status})`);
      }

      const payload = await res.json();
      const q = payload?.data;
      setResults(
        q
          ? [
              {
                symbol: q.symbol,
                currentPrice: q.currentPrice,
                percentChange: q.percentChange,
              },
            ]
          : []
      );
    } catch (e) {
      setError(e.message || 'Failed to search symbol');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const run = async () => {
      await fetchTrending();
    };
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const formatPrice = (n) => {
    if (typeof n !== 'number' || Number.isNaN(n)) return '—';
    return n.toFixed(2);
  };

  const formatPercent = (n) => {
    if (typeof n !== 'number' || Number.isNaN(n)) return '—';
    // Finnhub dp is percent (e.g., 1.23 => 1.23%)
    const sign = n > 0 ? '+' : '';
    return `${sign}${n.toFixed(2)}%`;
  };

  return (
    <aside className={fullPage ? "h-full w-full bg-slate-900" : "h-full w-[420px] max-w-[85vw] bg-slate-900 border-l border-slate-700"}>
      <div className="h-full flex flex-col">
        <div className="p-5 border-b border-slate-700">
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-bold text-emerald-400">Financial Telemetry</h2>
            <div className="text-xs text-slate-500">Finnhub Quotes</div>
          </div>

          <form onSubmit={submitSearch} className="mt-4 flex gap-2">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search ticker (e.g., AAPL)"
              className="flex-1 bg-slate-950 border border-slate-700 rounded px-3 py-2 text-white placeholder:text-slate-500 focus:outline-none focus:border-emerald-500"
            />
            <button
              type="submit"
              className="bg-emerald-600 hover:bg-emerald-500 text-white font-semibold px-4 py-2 rounded border border-emerald-500/40 transition-colors"
            >
              Search
            </button>
          </form>
        </div>

        <div className="p-5 flex-1 overflow-auto">
          {loading && results.length === 0 && (
            <div className="text-slate-400 text-sm">Loading quotes...</div>
          )}
          {error && (
            <div className="mb-4 p-3 bg-red-900/40 border border-red-800 text-red-200 rounded text-sm">
              {error}
            </div>
          )}

          <div className="grid grid-cols-1 gap-3">
            {results.map((q) => {
              const positive = typeof q.percentChange === 'number' && q.percentChange >= 0;
              const colorClass = positive ? 'text-emerald-400 bg-emerald-900/30 border-emerald-800/50' : 'text-red-400 bg-red-900/30 border-red-800/50';

              return (
                <div
                  key={q.symbol}
                  className="bg-slate-950 border border-slate-800 rounded-lg p-4 flex items-center justify-between gap-3"
                >
                  <div>
                    <div className="text-white font-bold tracking-wide">{q.symbol}</div>
                    <div className="text-slate-300 text-sm">Current: ${formatPrice(q.currentPrice)}</div>
                  </div>
                  <div
                    className={`min-w-[96px] text-right rounded-md border px-3 py-2 text-sm font-semibold ${colorClass}`}
                  >
                    {formatPercent(q.percentChange)}
                  </div>
                </div>
              );
            })}

            {!loading && results.length === 0 && !error && (
              <div className="text-slate-500 text-sm">No quote data.</div>
            )}
          </div>
        </div>
      </div>
    </aside>
  );
};

