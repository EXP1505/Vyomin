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

const formatEpochSeconds = (epochSeconds) => {
  if (typeof epochSeconds !== 'number' || Number.isNaN(epochSeconds)) return '—';
  return new Date(epochSeconds * 1000).toLocaleString();
};

const CandlestickBar = (props) => {
  const { x, width, payload } = props;
  if (!payload || !width) return null;

  const { open, close, high, low } = payload;
  if ([open, close, high, low].some(v => v == null)) return null;

  const bullish = close >= open;
  const color = bullish ? '#34d399' : '#f87171';

  const yScale = props.yAxisMap?.[0]?.scale ?? props.yAxisMap?.['0']?.scale;
  if (!yScale) return null;

  const xCenter = x + width / 2;
  const bodyW = Math.max(3, width * 0.6);
  const bodyX = xCenter - bodyW / 2;

  const yHigh = yScale(high);
  const yLow = yScale(low);
  const yOpen = yScale(open);
  const yClose = yScale(close);

  const bodyTop = Math.min(yOpen, yClose);
  const bodyBottom = Math.max(yOpen, yClose);
  const bodyH = Math.max(1, bodyBottom - bodyTop);

  return (
    <g>
      <line x1={xCenter} x2={xCenter} y1={yHigh} y2={bodyTop} stroke={color} strokeWidth={1.5} />
      <line x1={xCenter} x2={xCenter} y1={bodyBottom} y2={yLow} stroke={color} strokeWidth={1.5} />
      <rect x={bodyX} y={bodyTop} width={bodyW} height={bodyH} fill={color} stroke={color} strokeWidth={0.5} />
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
        if (!cancelled) setError(e?.message || 'Failed to load stock details');
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    run();
    return () => { cancelled = true; };
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
    if (!candles) return [];
    const t = candles?.t;
    const open = candles?.o;
    const high = candles?.h;
    const low = candles?.l;
    const close = candles?.c;

    if (!Array.isArray(t) || !Array.isArray(open) || !Array.isArray(high) || !Array.isArray(low) || !Array.isArray(close)) {
      return [];
    }

    return t.map((ts, i) => ({
      ts,
      open: open[i],
      high: high[i],
      low: low[i],
      close: close[i],
    }))
    .filter(p => p.open != null && p.close != null && p.high != null && p.low != null)
    .slice(-30);
  }, [candles]);

  const lastNews = useMemo(() => {
    if (!news) return [];
    const items = Array.isArray(news) ? news : [];
    return items.slice(0, 5);
  }, [news]);

  return (
    <div className="fixed inset-0 z-50">
      <div
        className="absolute inset-0 bg-black/70"
        onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}
      />
      <div
        className="absolute inset-0 flex items-center justify-center p-4"
        onMouseDown={(e) => { e.stopPropagation(); }}
      >
        <div className="w-full max-w-[900px] max-h-[90vh] overflow-y-auto bg-slate-900 border border-slate-800 rounded-xl">
          <div className="p-4 md:p-5 flex items-start justify-between gap-4 border-b border-slate-800 sticky top-0 bg-slate-900 z-10">
            <div className="min-w-0">
              <div className="text-emerald-400 font-bold tracking-wide">{symbol}</div>
              <div className="text-slate-400 text-sm">Financial details</div>
            </div>
            <button
              onClick={onClose}
              className="shrink-0 text-slate-300 hover:text-white rounded-md border border-slate-700 hover:border-slate-600 px-3 py-2"
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
              </div>
            )}

            {!loading && error && (
              <div className="mb-4 p-3 bg-red-900/40 border border-red-800 text-red-200 rounded text-sm">{error}</div>
            )}

            {!loading && !error && (
              <div className="grid grid-cols-1 gap-4">
                {/* Company header */}
                <div className="bg-slate-950 border border-slate-800 rounded-lg p-4 flex items-center gap-4">
                  <div className="w-14 h-14 rounded bg-slate-800/40 flex items-center justify-center overflow-hidden border border-slate-800 shrink-0">
                    {company?.logo ? (
                      <img src={company.logo} alt="logo" className="w-full h-full object-contain" />
                    ) : (
                      <div className="text-slate-500 text-xs">No logo</div>
                    )}
                  </div>
                  <div className="min-w-0">
                    <div className="text-white font-bold truncate">{company?.name || symbol}</div>
                    <div className="text-slate-300 text-sm truncate">{company?.industry || '—'}</div>
                    <div className="text-slate-500 text-xs truncate">{company?.exchange || '—'}</div>
                  </div>
                </div>

                {/* Candlestick chart */}
                <div className="bg-slate-950 border border-slate-800 rounded-lg p-4">
                  <div className="text-slate-300 text-sm mb-2">Last 30 trading days</div>
                  <div className="h-[320px]">
                    <ResponsiveContainer width="100%" height="100%">
                      <ComposedChart data={chartData} margin={{ top: 10, right: 10, left: 10, bottom: 0 }}>
                        <CartesianGrid stroke="#1e293b" strokeDasharray="3 3" />
                        <XAxis
                          dataKey="ts"
                          tickFormatter={(v) => {
                            const d = new Date(v * 1000);
                            return `${d.getMonth() + 1}/${d.getDate()}`;
                          }}
                          minTickGap={25}
                          tick={{ fill: '#64748b', fontSize: 10 }}
                        />
                        <YAxis
                          tick={{ fill: '#64748b', fontSize: 10 }}
                          width={70}
                          tickFormatter={(v) => `$${v.toFixed(0)}`}
                          domain={([min, max]) => [Math.floor(min * 0.998), Math.ceil(max * 1.002)]}
                        />
                        <Tooltip
                          content={({ active, payload }) => {
                            if (!active || !payload?.length) return null;
                            const p = payload[0]?.payload;
                            const bullish = p?.close >= p?.open;
                            return (
                              <div className="bg-slate-900 border border-slate-700 text-slate-100 p-3 rounded-lg shadow text-xs">
                                <div className="text-slate-400 mb-1">
                                  {new Date(p?.ts * 1000).toLocaleDateString()}
                                </div>
                                <div className={bullish ? 'text-emerald-400' : 'text-red-400'}>
                                  {bullish ? '▲ Bullish' : '▼ Bearish'}
                                </div>
                                <div className="mt-1 space-y-0.5">
                                  <div>O: <span className="text-white">${p?.open?.toFixed(2)}</span></div>
                                  <div>H: <span className="text-white">${p?.high?.toFixed(2)}</span></div>
                                  <div>L: <span className="text-white">${p?.low?.toFixed(2)}</span></div>
                                  <div>C: <span className="text-white">${p?.close?.toFixed(2)}</span></div>
                                </div>
                              </div>
                            );
                          }}
                        />
                        <Bar
                          dataKey="close"
                          shape={(shapeProps) => <CandlestickBar {...shapeProps} />}
                          isAnimationActive={false}
                        />
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>
                </div>

                {/* News */}
                <div className="bg-slate-950 border border-slate-800 rounded-lg p-4">
                  <div className="text-slate-300 text-sm mb-3">Latest news</div>
                  <div className="space-y-3">
                    {lastNews.length === 0 && (
                      <div className="text-slate-500 text-sm">No news available.</div>
                    )}
                    {lastNews.map((n, idx) => {
                      const image = n?.image || n?.imageUrl || null;
                      const url = n?.url || n?.link;
                      const datetime = typeof n?.datetime === 'number'
                        ? formatEpochSeconds(n.datetime)
                        : n?.datetime;

                      return (
                        <a
                          key={`${n?.id || idx}`}
                          href={url || '#'}
                          target="_blank"
                          rel="noreferrer"
                          className="block border border-slate-800 rounded-lg p-3 hover:border-emerald-500/40 transition-colors"
                          onClick={(e) => { if (!url) e.preventDefault(); }}
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
                              <div className="text-slate-500 text-xs mt-1">{n?.source || '—'}</div>
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