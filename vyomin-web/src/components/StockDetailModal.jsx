import { useEffect, useMemo, useState } from 'react';
import {
  ResponsiveContainer,
  ComposedChart,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  Bar,
} from 'recharts';
import { useAuthStore } from '../store/authStore';


const formatPrice = (n) => {
  if (typeof n !== 'number' || Number.isNaN(n)) return '—';
  return n.toLocaleString(undefined, { maximumFractionDigits: 4 });
};

const formatEpochSeconds = (epochSeconds) => {
  if (typeof epochSeconds !== 'number' || Number.isNaN(epochSeconds)) return '—';
  return new Date(epochSeconds * 1000).toLocaleString();
};

const Candlestick = (props) => {
  const { x, width, payload, yAxisMap } = props;
  if (!payload || width == null || width <= 0) return null;

  // Candles will be in our data with: open, high, low, close
  const { open, close, high, low } = payload;
  const bullish = close >= open;
  const color = bullish ? '#34d399' : '#f87171'; // emerald-400 / red-400-ish

  // We need pixel positions; Recharts provides scaled y positions via yAxisMap.
  // In practice, when Bar has a dataKey, recharts will call our shape with y/height.
  // For robustness, if yAxisMap is missing, we fallback to using y/height from props.
  const yScale = yAxisMap?.[0] ?? yAxisMap?.["0"];

  const toYPx = (v) => {
    if (!yScale) return props.y; // fallback
    return yScale.scale(v);
  };

  const xCenter = x + width / 2;
  const bodyW = Math.max(2, width * 0.65);
  const bodyX = xCenter - bodyW / 2;

  const yHigh = toYPx(high);
  const yLow = toYPx(low);
  const yOpen = toYPx(open);
  const yClose = toYPx(close);

  const wickY1 = Math.min(yHigh, yLow);
  const wickY2 = Math.max(yHigh, yLow);

  const bodyTop = Math.min(yOpen, yClose);
  const bodyBottom = Math.max(yOpen, yClose);
  const bodyH = Math.max(1, bodyBottom - bodyTop);

  return (
    <g>
      {/* wick */}
      <line x1={xCenter} x2={xCenter} y1={wickY1} y2={wickY2} stroke={color} strokeWidth={1} />
      {/* body */}
      <rect x={bodyX} y={bodyTop} width={bodyW} height={bodyH} fill={color} />
    </g>
  );
};

const SkeletonLine = ({ className = '' }) => (
  <div className={`h-3 bg-slate-800/70 rounded animate-pulse ${className}`} />
);

export default function StockDetailModal({ symbol, onClose }) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [profile, setProfile] = useState(null);
  const [candles, setCandles] = useState(null);
  const [news, setNews] = useState(null);

  const token = useAuthStore((s) => s.token);


  useEffect(() => {
    let cancelled = false;

    const run = async () => {
      setLoading(true);
      setError(null);
      setProfile(null);
      setCandles(null);
      setNews(null);

      try {
        const headers = {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        };

        const [pRes, cRes, nRes] = await Promise.all([
          fetch(`/api/intel/finance/profile?symbol=${encodeURIComponent(symbol)}`, { headers }),
          fetch(`/api/intel/finance/candles?symbol=${encodeURIComponent(symbol)}`, { headers }),
          fetch(`/api/intel/finance/news?symbol=${encodeURIComponent(symbol)}`, { headers }),
        ]);

        const [pJson, cJson, nJson] = await Promise.all([pRes.json(), cRes.json(), nRes.json()]);

        if (!pRes.ok) throw new Error(pJson?.error || `Failed profile (${pRes.status})`);
        if (!cRes.ok) throw new Error(cJson?.error || `Failed candles (${cRes.status})`);
        if (!nRes.ok) throw new Error(nJson?.error || `Failed news (${nRes.status})`);

        if (cancelled) return;
        setProfile(pJson?.data ?? null);
        setCandles(cJson?.data ?? null);
        setNews(nJson?.data ?? null);
      } catch (e) {
        if (!cancelled) {
          setError(e?.message || 'Failed to load stock details');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };


    run();

    return () => {
      cancelled = true;
    };
  }, [symbol, token]);

  const company = useMemo(() => {
    if (!profile) return null;
    return {
      name: profile?.name,
      ticker: profile?.ticker,
      exchange: profile?.exchange,
      industry: profile?.finnhubIndustry,
      logo: profile?.logo,
    };
  }, [profile]);

  const chartData = useMemo(() => {
    // Finnhub /stock/candle returns { c: [...], h: [...], l: [...], o: [...], t: [...] }
    if (!candles) return [];

    const t = candles?.t;
    const open = candles?.o;
    const high = candles?.h;
    const low = candles?.l;
    const close = candles?.c;

    if (!Array.isArray(t) || !Array.isArray(open) || !Array.isArray(high) || !Array.isArray(low) || !Array.isArray(close)) {
      return [];
    }

    // chartData memo — add the filter
    const points = t.map((ts, i) => ({
      ts,
      open: open[i],
      high: high[i],
      low: low[i],
      close: close[i],
    })).filter(p => p.open != null && p.close != null && p.high != null && p.low != null);

    // Ensure last ~30 days if more returned.
    return points.slice(-30);
  }, [candles]);

  // lastNews memo — Finnhub returns a plain array, not { news: [...] }
  const lastNews = useMemo(() => {
    if (!news) return [];
    const items = Array.isArray(news) ? news : [];
    return items.slice(0, 5);
  }, [news]);

  return (
    <div className="fixed inset-0 z-50">

      <div
        className="absolute inset-0 bg-black/70"
        onMouseDown={(e) => {
          // close only if backdrop itself was clicked
          if (e.target === e.currentTarget) onClose();
        }}
      />

      <div
        className="absolute inset-0 flex items-center justify-center p-4"
        onMouseDown={(e) => {
          // prevent backdrop click bubbling
          e.stopPropagation();
        }}
      >
        <div className="w-full max-w-[900px] h-full md:h-auto overflow-y-auto bg-slate-900 border border-slate-800 rounded-lg md:rounded-xl">
          <div className="p-4 md:p-5 flex items-start justify-between gap-4 border-b border-slate-800">
            <div className="min-w-0">
              <div className="text-emerald-400 font-bold tracking-wide">{symbol}</div>
              <div className="text-slate-400 text-sm">Financial details</div>
            </div>

            <button
              onClick={onClose}
              className="shrink-0 text-slate-300 hover:text-white rounded-md border border-slate-700 hover:border-slate-600 px-3 py-2"
              aria-label="Close"
            >
              ✕
            </button>
          </div>

          <div className="p-4 md:p-5">
            {loading && (
              <div className="space-y-4">
                <div className="flex items-center gap-4">
                  <div className="w-14 h-14 rounded bg-slate-800/60 animate-pulse" />
                  <div className="flex-1">
                    <SkeletonLine className="w-2/3" />
                    <SkeletonLine className="w-1/2 mt-2" />
                    <SkeletonLine className="w-1/3 mt-2" />
                  </div>
                </div>

                <div className="bg-slate-950 border border-slate-800 rounded-lg p-3">
                  <SkeletonLine className="w-full" />
                  <SkeletonLine className="w-full mt-3" />
                  <SkeletonLine className="w-full mt-3" />
                </div>

                <div className="bg-slate-950 border border-slate-800 rounded-lg p-3">
                  <SkeletonLine className="w-2/3" />
                  <SkeletonLine className="w-full mt-2" />
                  <SkeletonLine className="w-full mt-2" />
                  <SkeletonLine className="w-3/4 mt-2" />
                </div>
              </div>
            )}

            {!loading && error && (
              <div className="mb-4 p-3 bg-red-900/40 border border-red-800 text-red-200 rounded text-sm">{error}</div>
            )}

            {!loading && !error && (
              <div className="grid grid-cols-1 gap-4">
                {/* Company header */}
                <div className="bg-slate-950 border border-slate-800 rounded-lg p-4 flex items-center gap-4">
                  <div className="w-14 h-14 rounded bg-slate-800/40 flex items-center justify-center overflow-hidden border border-slate-800">
                    {company?.logo ? (
                      <img src={company.logo} alt="Company logo" className="w-full h-full object-contain" />
                    ) : (
                      <div className="text-slate-500 text-xs">No logo</div>
                    )}
                  </div>

                  <div className="min-w-0">
                    <div className="text-white font-bold truncate">{company?.name || company?.ticker || symbol}</div>
                    <div className="text-slate-300 text-sm truncate">{company?.industry || '—'}</div>
                    <div className="text-slate-500 text-xs truncate">{company?.exchange || '—'}</div>
                  </div>
                </div>

                {/* Candlestick */}
                <div className="bg-slate-950 border border-slate-800 rounded-lg p-4">
                  <div className="text-slate-300 text-sm mb-2">Last 30 trading days</div>
                  <div className="h-[320px]">
                    <ResponsiveContainer width="100%" height="100%">
                      <ComposedChart data={chartData}>
                        <CartesianGrid stroke="#1f2937" strokeDasharray="3 3" />
                        <XAxis
                          dataKey="ts"
                          tickFormatter={(v) => {
                            const d = new Date(v * 1000);
                            return d.getDate();
                          }}
                          minTickGap={20}
                          tick={{ fill: '#94a3b8', fontSize: 11 }}
                        />
                        <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} width={60} domain={['dataMin', 'dataMax']} />
                        <Tooltip
                          content={({ active, payload }) => {

                            if (!active || !payload?.length) return null;
                            const p = payload[0]?.payload;
                            return (
                              <div className="bg-slate-900 border border-slate-700 text-slate-100 p-3 rounded-lg shadow">
                                <div className="text-xs text-slate-400">{formatEpochSeconds(p?.ts)}</div>
                                <div className="font-semibold">Open: {formatPrice(p?.open)}</div>
                                <div className="text-xs">High: {formatPrice(p?.high)}</div>
                                <div className="text-xs">Low: {formatPrice(p?.low)}</div>
                                <div className="text-xs">Close: {formatPrice(p?.close)}</div>
                              </div>
                            );
                          }}
                        />

                        {/* We use Bar as a hook for a custom candlestick SVG */}
                        <Bar
                          dataKey="close"
                          shape={(shapeProps) => <Candlestick {...shapeProps} />}
                        />
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>
                </div>

                {/* News */}
                <div className="bg-slate-950 border border-slate-800 rounded-lg p-4">
                  <div className="text-slate-300 text-sm mb-3">Latest news</div>
                  <div className="space-y-3">
                    {lastNews.length === 0 && <div className="text-slate-500 text-sm">No news available.</div>}
                    {lastNews.map((n, idx) => {
                      const image = n?.image || n?.imageUrl || null;
                      const url = n?.url || n?.link;
                      const datetime = typeof n?.datetime === 'number' ? formatEpochSeconds(n.datetime) : n?.datetime;

                      return (
                        <a
                          key={`${n?.id || idx}-${n?.headline || 'news'}`}
                          href={url || '#'}
                          target="_blank"
                          rel="noreferrer"
                          className="block border border-slate-800 rounded-lg p-3 hover:border-emerald-500/40 transition-colors"
                          onClick={(e) => {
                            if (!url) e.preventDefault();
                          }}
                        >
                          <div className="flex gap-3">
                            {image ? (
                              <img src={image} alt="news" className="w-14 h-14 rounded object-cover shrink-0" />
                            ) : (
                              <div className="w-14 h-14 rounded bg-slate-800/40 shrink-0 flex items-center justify-center text-slate-500 text-xs">
                                N/A
                              </div>
                            )}

                            <div className="min-w-0">
                              <div className="text-white font-semibold text-sm line-clamp-2">{n?.headline || '—'}</div>
                              <div className="text-slate-500 text-xs mt-1">{n?.source || n?.sourceId || '—'}</div>
                              <div className="text-slate-400 text-xs mt-1">{datetime}</div>
                            </div>
                          </div>
                        </a>
                      );
                    })}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

