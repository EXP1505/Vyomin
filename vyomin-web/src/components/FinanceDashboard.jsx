import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { useBookmarkStore, useBookmarkedStockSymbols } from '../store/bookmarkStore';
import { useRecentConflicts } from '../hooks/useRecentConflicts';
import StockDetailModal from './StockDetailModal';
import { Panel } from './design/Panel';
import { Sparkline } from './design/Sparkline';

function BookmarkStar({ active, onToggle }) {
  return (
    <button
      onClick={(e) => { e.stopPropagation(); onToggle(); }}
      title={active ? 'Remove bookmark' : 'Bookmark this stock'}
      className="shrink-0 text-sm leading-none transition-colors"
      style={{ color: active ? 'var(--accent)' : 'var(--text-faint)' }}
    >
      {active ? '★' : '☆'}
    </button>
  );
}

function TickerCard({ q, onSelect }) {
  const positive = typeof q.percentChange === 'number' && q.percentChange >= 0;
  const color = positive ? 'var(--positive)' : 'var(--negative)';
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const bookmarked = useBookmarkStore((s) => s.isStockBookmarked(q.symbol));
  const toggleStock = useBookmarkStore((s) => s.toggleStock);
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
          <div className="flex items-center gap-1.5">
            <span className="font-mono-data text-sm font-semibold" style={{ color: 'var(--text)' }}>{q.symbol}</span>
            {isAuthenticated && <BookmarkStar active={bookmarked} onToggle={() => toggleStock(q.symbol)} />}
          </div>
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

const MOVER_PERIODS = [
  { key: 'day', label: 'Day' },
  { key: 'week', label: 'Week' },
  { key: 'month', label: 'Month' },
  { key: 'year', label: 'Year' },
];

function MoverRow({ m, onSelect }) {
  const positive = typeof m.percentChange === 'number' && m.percentChange >= 0;
  const color = positive ? 'var(--positive)' : 'var(--negative)';
  const formatPrice = (n) => (typeof n === 'number' && !Number.isNaN(n) ? n.toFixed(2) : '—');
  const formatPercent = (n) => {
    if (typeof n !== 'number' || Number.isNaN(n)) return '—';
    const sign = n > 0 ? '+' : '';
    return `${sign}${n.toFixed(2)}%`;
  };

  return (
    <button
      onClick={() => onSelect(m.symbol)}
      className="w-full flex items-center justify-between gap-2 px-3 py-2 border-b last:border-b-0 text-left transition-colors hover:bg-[var(--panel-2)]"
      style={{ borderColor: 'var(--hairline)' }}
    >
      <span className="font-mono-data text-sm font-semibold" style={{ color: 'var(--text)' }}>{m.symbol}</span>
      <span className="font-mono-data text-xs" style={{ color: 'var(--text-dim)' }}>${formatPrice(m.currentPrice)}</span>
      <span className="font-mono-data text-xs px-2 py-1 border" style={{ color, borderColor: color, background: `${color}18` }}>
        {formatPercent(m.percentChange)}
      </span>
    </button>
  );
}

function NewsCard({ n }) {
  const url = n?.url;
  const datetime = typeof n?.datetime === 'number' ? new Date(n.datetime * 1000).toLocaleString() : null;

  return (
    <a
      href={url || '#'}
      target="_blank"
      rel="noreferrer"
      className="block border p-3 transition-colors hover:border-[var(--accent)]"
      style={{ borderColor: 'var(--hairline)' }}
      onClick={(e) => { if (!url) e.preventDefault(); }}
    >
      <div className="flex gap-3">
        {n?.image ? (
          <img src={n.image} alt="" className="w-16 h-16 object-cover shrink-0" />
        ) : (
          <div className="w-16 h-16 shrink-0 flex items-center justify-center text-xs" style={{ background: 'var(--panel-2)', color: 'var(--text-faint)' }}>
            N/A
          </div>
        )}
        <div className="min-w-0">
          <div className="font-semibold text-sm line-clamp-2" style={{ color: 'var(--text)' }}>{n?.headline || '—'}</div>
          <div className="text-xs mt-1" style={{ color: 'var(--text-faint)' }}>{n?.source || '—'}</div>
          {datetime && <div className="text-xs mt-1" style={{ color: 'var(--text-dim)' }}>{datetime}</div>}
        </div>
      </div>
    </a>
  );
}

export const FinanceDashboard = ({ fullPage = false }) => {
  const navigate = useNavigate();
  const onJumpToGraph = useCallback(
    (query, type = 'conflict') => navigate('/graph', { state: { query, type, ts: Date.now() } }),
    [navigate]
  );
  const recentConflicts = useRecentConflicts();
  const token = useAuthStore((s) => s.token);
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const bookmarkedSymbolsKey = useBookmarkedStockSymbols();
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [results, setResults] = useState([]);
  const [resultsLabel, setResultsLabel] = useState('Trending');
  const [selectedSymbol, setSelectedSymbol] = useState(null);

  const [marketNews, setMarketNews] = useState([]);
  const [newsLoading, setNewsLoading] = useState(false);
  const [newsError, setNewsError] = useState(null);

  const [moversPeriod, setMoversPeriod] = useState('day');
  const [movers, setMovers] = useState({ gainers: [], losers: [] });
  const [moversLoading, setMoversLoading] = useState(false);
  const [moversError, setMoversError] = useState(null);

  const authHeaders = useMemo(() => (token ? { Authorization: `Bearer ${token}` } : {}), [token]);
  // Guards against fetchTrending/fetchWatchlist racing: whichever call is the most recently
  // started is the only one allowed to commit its results, even if an earlier call's network
  // round-trip happens to resolve later.
  const resultsRequestIdRef = useRef(0);

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
    const requestId = ++resultsRequestIdRef.current;
    setLoading(true);
    setError(null);
    setResultsLabel('Trending');
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
      const withSeries = await attachSparkline(rows);
      if (requestId === resultsRequestIdRef.current) setResults(withSeries);
    } catch (e) {
      if (requestId === resultsRequestIdRef.current) setError(e.message || 'Failed to fetch trending');
    } finally {
      if (requestId === resultsRequestIdRef.current) setLoading(false);
    }
  };

  const fetchWatchlist = async (symbols) => {
    const requestId = ++resultsRequestIdRef.current;
    setLoading(true);
    setError(null);
    setResultsLabel('Your Watchlist');
    try {
      const quotes = await Promise.all(
        symbols.map(async (symbol) => {
          try {
            const res = await fetch(`/api/intel/finance/search?symbol=${encodeURIComponent(symbol)}`, {
              headers: { 'Content-Type': 'application/json', ...authHeaders },
            });
            if (!res.ok) return null;
            const payload = await res.json();
            const q = payload?.data;
            return q ? { symbol: q.symbol, currentPrice: q.currentPrice, percentChange: q.percentChange } : null;
          } catch {
            return null;
          }
        })
      );
      const rows = quotes.filter(Boolean);
      const withSeries = await attachSparkline(rows);
      if (requestId === resultsRequestIdRef.current) setResults(withSeries);
    } catch (e) {
      if (requestId === resultsRequestIdRef.current) setError(e.message || 'Failed to fetch watchlist');
    } finally {
      if (requestId === resultsRequestIdRef.current) setLoading(false);
    }
  };

  const submitSearch = async (e) => {
    e.preventDefault();
    const symbol = query.trim().toUpperCase();
    if (!symbol) return;

    const requestId = ++resultsRequestIdRef.current;
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
      const withSeries = await attachSparkline(rows);
      if (requestId === resultsRequestIdRef.current) {
        setResultsLabel('Search Result');
        setResults(withSeries);
      }
      if (q) setSelectedSymbol(q.symbol);
    } catch (e) {
      if (requestId === resultsRequestIdRef.current) setError(e.message || 'Failed to search symbol');
    } finally {
      if (requestId === resultsRequestIdRef.current) setLoading(false);
    }
  };

  const fetchMarketNews = async () => {
    setNewsLoading(true);
    setNewsError(null);
    try {
      const res = await fetch('/api/intel/finance/market-news', { headers: authHeaders });
      const payload = await res.json();
      if (!res.ok) throw new Error(payload?.error || `Failed to fetch news (${res.status})`);
      const items = Array.isArray(payload?.data) ? payload.data : [];
      setMarketNews(items.slice(0, 8));
    } catch (e) {
      setNewsError(e.message || 'Failed to fetch market news');
    } finally {
      setNewsLoading(false);
    }
  };

  const fetchMovers = async (period) => {
    setMoversLoading(true);
    setMoversError(null);
    try {
      const res = await fetch(`/api/intel/finance/movers?period=${encodeURIComponent(period)}`, { headers: authHeaders });
      const payload = await res.json();
      if (!res.ok) throw new Error(payload?.error || `Failed to fetch movers (${res.status})`);
      setMovers({ gainers: payload?.data?.gainers || [], losers: payload?.data?.losers || [] });
    } catch (e) {
      setMoversError(e.message || 'Failed to fetch movers');
    } finally {
      setMoversLoading(false);
    }
  };

  useEffect(() => {
    const symbols = bookmarkedSymbolsKey ? bookmarkedSymbolsKey.split(',') : [];
    if (isAuthenticated && symbols.length > 0) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      fetchWatchlist(symbols);
    } else {
      fetchTrending();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bookmarkedSymbolsKey, isAuthenticated]);

  useEffect(() => {
    if (!fullPage) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchMarketNews();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fullPage]);

  useEffect(() => {
    if (!fullPage) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchMovers(moversPeriod);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [moversPeriod, fullPage]);

  return (
    <>
      <Panel
        as="aside"
        className={fullPage ? 'flex h-full w-full flex-col' : 'flex h-full w-[420px] max-w-[85vw] flex-col'}
      >
        <div className="p-5 border-b" style={{ borderColor: 'var(--hairline)' }}>
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold" style={{ color: 'var(--text)' }}>Financial Telemetry</h2>
            <div className="font-mono-data text-xs uppercase" style={{ color: 'var(--text-faint)' }}>{resultsLabel}</div>
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

          {fullPage && (
            <div className="mt-8 grid grid-cols-1 lg:grid-cols-2 gap-6">
              <div>
                <div className="flex items-center justify-between mb-3">
                  <h3 className="text-sm font-semibold" style={{ color: 'var(--text)' }}>Big-Cap Movers</h3>
                  <div className="flex gap-1">
                    {MOVER_PERIODS.map((p) => (
                      <button
                        key={p.key}
                        onClick={() => setMoversPeriod(p.key)}
                        className="font-mono-data text-xs px-2 py-1 border transition-colors"
                        style={
                          moversPeriod === p.key
                            ? { borderColor: 'var(--accent)', color: 'var(--accent)', background: 'var(--accent-soft)' }
                            : { borderColor: 'var(--hairline)', color: 'var(--text-dim)' }
                        }
                      >
                        {p.label}
                      </button>
                    ))}
                  </div>
                </div>

                {moversError && (
                  <div className="mb-3 p-3 border text-sm" style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}>
                    {moversError}
                  </div>
                )}

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <Panel>
                    <div className="px-3 py-2 border-b text-xs font-semibold" style={{ borderColor: 'var(--hairline)', color: 'var(--positive)' }}>
                      TOP PERFORMERS
                    </div>
                    {moversLoading && movers.gainers.length === 0 && (
                      <div className="px-3 py-3 text-sm" style={{ color: 'var(--text-dim)' }}>Loading...</div>
                    )}
                    {!moversLoading && movers.gainers.length === 0 && !moversError && (
                      <div className="px-3 py-3 text-sm" style={{ color: 'var(--text-faint)' }}>No data.</div>
                    )}
                    {movers.gainers.map((m) => (
                      <MoverRow key={m.symbol} m={m} onSelect={setSelectedSymbol} />
                    ))}
                  </Panel>

                  <Panel>
                    <div className="px-3 py-2 border-b text-xs font-semibold" style={{ borderColor: 'var(--hairline)', color: 'var(--negative)' }}>
                      STRUGGLING BIG CAPS
                    </div>
                    {moversLoading && movers.losers.length === 0 && (
                      <div className="px-3 py-3 text-sm" style={{ color: 'var(--text-dim)' }}>Loading...</div>
                    )}
                    {!moversLoading && movers.losers.length === 0 && !moversError && (
                      <div className="px-3 py-3 text-sm" style={{ color: 'var(--text-faint)' }}>No data.</div>
                    )}
                    {movers.losers.map((m) => (
                      <MoverRow key={m.symbol} m={m} onSelect={setSelectedSymbol} />
                    ))}
                  </Panel>
                </div>

                <h3 className="text-sm font-semibold mt-6 mb-3" style={{ color: 'var(--text)' }}>Related Conflict Signals</h3>
                <Panel>
                  {recentConflicts.length === 0 && (
                    <div className="px-3 py-3 text-sm" style={{ color: 'var(--text-faint)' }}>No recent conflict signals.</div>
                  )}
                  {recentConflicts.slice(0, 8).map((c) => (
                    <button
                      key={c.id ?? c.gdeltEventId}
                      onClick={() => onJumpToGraph(c.name, 'conflict')}
                      className="w-full flex items-center justify-between gap-2 px-3 py-2 border-b last:border-b-0 text-left transition-colors hover:bg-[var(--panel-2)]"
                      style={{ borderColor: 'var(--hairline)' }}
                    >
                      <span className="text-sm truncate" style={{ color: 'var(--text)' }}>{c.name}</span>
                      {c.dateReported && (
                        <span className="font-mono-data text-xs shrink-0" style={{ color: 'var(--text-faint)' }}>
                          {new Date(c.dateReported).toLocaleDateString()}
                        </span>
                      )}
                    </button>
                  ))}
                </Panel>
              </div>

              <div>
                <h3 className="text-sm font-semibold mb-3" style={{ color: 'var(--text)' }}>Top Market News</h3>
                {newsError && (
                  <div className="mb-3 p-3 border text-sm" style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}>
                    {newsError}
                  </div>
                )}
                <div className="space-y-3 max-h-[600px] overflow-y-auto pr-1">
                  {newsLoading && marketNews.length === 0 && (
                    <div className="text-sm" style={{ color: 'var(--text-dim)' }}>Loading news...</div>
                  )}
                  {!newsLoading && marketNews.length === 0 && !newsError && (
                    <div className="text-sm" style={{ color: 'var(--text-faint)' }}>No news available.</div>
                  )}
                  {marketNews.map((n, idx) => (
                    <NewsCard key={n?.id || idx} n={n} />
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>
      </Panel>

      {selectedSymbol ? (
        <StockDetailModal symbol={selectedSymbol} onClose={() => setSelectedSymbol(null)} onJumpToGraph={onJumpToGraph} />
      ) : null}
    </>
  );
};
