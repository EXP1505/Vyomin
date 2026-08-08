import { useEffect, useMemo, useRef, useState } from 'react';
import { useAuthStore } from '../store/authStore';

const formatEpochSeconds = (epochSeconds) => {

  if (typeof epochSeconds !== 'number' || Number.isNaN(epochSeconds)) return '—';
  return new Date(epochSeconds * 1000).toLocaleString();
};



const SkeletonLine = ({ className = '' }) => (
  <div className={`h-3 bg-slate-800/70 rounded animate-pulse ${className}`} />
);

function CandlestickSvgChart({ chartData }) {
  const containerRef = useRef(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [hover, setHover] = useState({ idx: null, x: 0, y: 0 });

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const ro = new ResizeObserver((entries) => {
      const cr = entries[0]?.contentRect;
      if (!cr) return;
      setSize({ width: cr.width, height: cr.height });
    });

    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const W = size.width;
  const H = size.height;

  const padding = { top: 12, right: 14, bottom: 28, left: 70 };
  const innerW = Math.max(0, W - padding.left - padding.right);
  const innerH = Math.max(0, H - padding.top - padding.bottom);

  const candles = useMemo(() => (Array.isArray(chartData) ? chartData : []), [chartData]);

  const { minY, maxY } = useMemo(() => {
    if (!candles.length) return { minY: 0, maxY: 1 };
    let min = Infinity;
    let max = -Infinity;
    for (const c of candles) {
      const l = c?.low;
      const h = c?.high;
      if (l != null && l < min) min = l;
      if (h != null && h > max) max = h;
    }
    if (!Number.isFinite(min) || !Number.isFinite(max) || min === max) {
      return { minY: min || 0, maxY: (max || min || 1) + 1 };
    }
    return { minY: min * 0.998, maxY: max * 1.002 };
  }, [candles]);

  const yToPx = (v) => {
    const t = (v - minY) / (maxY - minY);
    return padding.top + (1 - t) * innerH;
  };

  const handleMove = (e) => {
    if (!containerRef.current || !candles.length) return;
    const rect = containerRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const relX = x - padding.left;
    const step = innerW / Math.max(1, candles.length);
    const idx = Math.min(candles.length - 1, Math.max(0, Math.floor(relX / step)));
    const cx = padding.left + step * (idx + 0.5);
    const cy = padding.top;
    setHover({ idx, x: cx, y: cy });
  };

  const handleLeave = () => setHover({ idx: null, x: 0, y: 0 });

  const bullishColor = '#35d6b8';
  const bearishColor = '#ff5c6c';

  const tickCount = 4;
  const yTicks = Array.from({ length: tickCount + 1 }, (_, i) => {
    const t = i / tickCount;
    const v = minY + (maxY - minY) * (1 - t);
    return { v, y: padding.top + t * innerH };
  });

  const tooltip = (() => {
    const idx = hover.idx;
    if (idx == null || !candles[idx]) return null;
    const p = candles[idx];
    const bullish = p.close >= p.open;
    return {
      p,
      bullish,
    };
  })();

  const step = innerW / Math.max(1, candles.length);
  const bodyW = Math.max(3, step * 0.6);

  return (
    <div
      ref={containerRef}
      className="w-full h-full relative"
      onMouseMove={handleMove}
      onMouseLeave={handleLeave}
      style={{
        background:
          'repeating-linear-gradient(to right, transparent, transparent 39px, rgba(124,134,152,0.08) 40px), repeating-linear-gradient(to bottom, transparent, transparent 39px, rgba(124,134,152,0.08) 40px)',
      }}
    >
      <svg width={W} height={H} className="block">
        {/* Grid */}
        {yTicks.map((t, i) => (
          <g key={i}>
            <line x1={padding.left} x2={padding.left + innerW} y1={t.y} y2={t.y} stroke="#1e222b" strokeDasharray="3 3" />
            <text x={padding.left - 10} y={t.y + 3} fill="#7c8698" fontSize="10" fontFamily="'JetBrains Mono', monospace" textAnchor="end">
              {`$${t.v.toFixed(0)}`}
            </text>
          </g>
        ))}

        {/* Candles */}
        {candles.map((c, i) => {
          const xCenter = padding.left + step * (i + 0.5);
          const wickTop = yToPx(c.high);
          const wickBottom = yToPx(c.low);

          const bullish = c.close >= c.open;
          const color = bullish ? bullishColor : bearishColor;

          const yOpen = yToPx(c.open);
          const yClose = yToPx(c.close);
          const bodyTop = Math.min(yOpen, yClose);
          const bodyBottom = Math.max(yOpen, yClose);
          const bodyH = Math.max(1, bodyBottom - bodyTop);
          const bodyX = xCenter - bodyW / 2;

          return (
            <g key={c.ts ?? i}>
              <line x1={xCenter} x2={xCenter} y1={wickTop} y2={wickBottom} stroke={color} strokeWidth={1.5} />
              <rect x={bodyX} y={bodyTop} width={bodyW} height={bodyH} fill={color} stroke={color} strokeWidth={0.5} />
            </g>
          );
        })}

        {/* X labels */}
        {candles.length > 0 &&
          (() => {
            const labelEvery = Math.max(1, Math.floor(candles.length / 6));
            return candles.map((c, i) => {
              if (i % labelEvery !== 0 && i !== candles.length - 1) return null;
              const d = new Date(c.ts * 1000);
              const label = `${d.getMonth() + 1}/${d.getDate()}`;
              const xCenter = padding.left + step * (i + 0.5);
              return (
                <text key={`x-${c.ts ?? i}`} x={xCenter} y={padding.top + innerH + 18} fill="#7c8698" fontSize="10" fontFamily="'JetBrains Mono', monospace" textAnchor="middle">
                  {label}
                </text>
              );
            });
          })()}
      </svg>

      {/* Tooltip */}
      {tooltip && (
        <div
          className="font-mono-data absolute z-20 border p-3 text-xs"
          style={{
            left: Math.min(W - 240, Math.max(8, hover.x - 120)),
            top: 10,
            background: 'var(--panel)',
            borderColor: 'var(--hairline)',
            color: 'var(--text)',
          }}
        >
          <div className="mb-1" style={{ color: 'var(--text-dim)' }}>{new Date(tooltip.p.ts * 1000).toLocaleDateString()}</div>
          <div style={{ color: tooltip.bullish ? 'var(--positive)' : 'var(--negative)' }}>
            {tooltip.bullish ? '▲ Bullish' : '▼ Bearish'}
          </div>
          <div className="mt-1 space-y-0.5">
            <div>O: <span style={{ color: 'var(--text)' }}>${tooltip.p.open?.toFixed(2)}</span></div>
            <div>H: <span style={{ color: 'var(--text)' }}>${tooltip.p.high?.toFixed(2)}</span></div>
            <div>L: <span style={{ color: 'var(--text)' }}>${tooltip.p.low?.toFixed(2)}</span></div>
            <div>C: <span style={{ color: 'var(--text)' }}>${tooltip.p.close?.toFixed(2)}</span></div>
          </div>
          <div className="text-xs mt-2" style={{ color: 'var(--text-faint)' }}>O = Open, H = High, L = Low, C = Close</div>
        </div>
      )}
    </div>
  );
}

export default function StockDetailModal({ symbol, onClose, onJumpToGraph }) {

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [profile, setProfile] = useState(null);
  const [candles, setCandles] = useState(null);
  const [news, setNews] = useState(null);
  const [relatedConflicts, setRelatedConflicts] = useState([]);

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

  // Cross-link: surface conflict events tied to this company/region from the Intelligence Graph data.
  useEffect(() => {
    const name = company?.name;
    if (!name) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setRelatedConflicts([]);
      return;
    }
    let cancelled = false;
    fetch(`http://localhost:8080/api/intel/search?q=${encodeURIComponent(name)}&type=conflict`)
      .then((r) => r.json())
      .then((data) => {
        if (cancelled) return;
        const conflicts = data?.results?.conflicts ?? [];
        setRelatedConflicts(conflicts.slice(0, 6));
      })
      .catch(() => {
        if (!cancelled) setRelatedConflicts([]);
      });
    return () => {
      cancelled = true;
    };
  }, [company?.name]);

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
        <div
          className="w-full max-w-[900px] max-h-[90vh] overflow-y-auto border"
          style={{ background: 'var(--panel)', borderColor: 'var(--hairline)' }}
        >
          <div
            className="p-4 md:p-5 flex items-start justify-between gap-4 border-b sticky top-0 z-10"
            style={{ borderColor: 'var(--hairline)', background: 'var(--panel)' }}
          >
            <div className="min-w-0">
              <div className="font-mono-data font-bold tracking-wide" style={{ color: 'var(--accent)' }}>{symbol}</div>
              <div className="text-sm" style={{ color: 'var(--text-dim)' }}>Financial details</div>
            </div>
            <button
              onClick={onClose}
              className="shrink-0 border px-3 py-2 transition-colors"
              style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
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
              <div className="mb-4 p-3 border text-sm" style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}>{error}</div>
            )}

            {!loading && !error && (
              <div className="grid grid-cols-1 gap-4">
                {/* Company header */}
                <div className="flex items-center gap-4 border p-4" style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)' }}>
                  <div className="w-14 h-14 flex items-center justify-center overflow-hidden border shrink-0" style={{ borderColor: 'var(--hairline)' }}>
                    {company?.logo ? (
                      <img src={company.logo} alt="logo" className="w-full h-full object-contain" />
                    ) : (
                      <div className="text-xs" style={{ color: 'var(--text-faint)' }}>No logo</div>
                    )}
                  </div>
                  <div className="min-w-0">
                    <div className="font-bold truncate" style={{ color: 'var(--text)' }}>{company?.name || symbol}</div>
                    <div className="text-sm truncate" style={{ color: 'var(--text-dim)' }}>{company?.industry || '—'}</div>
                    <div className="text-xs truncate" style={{ color: 'var(--text-faint)' }}>{company?.exchange || '—'}</div>
                  </div>
                </div>

                {/* Candlestick chart */}
                <div className="border p-4" style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)' }}>
                  <div className="text-sm mb-2" style={{ color: 'var(--text-dim)' }}>Last 30 trading days</div>
                  <div className="h-[320px]">
                    <CandlestickSvgChart chartData={chartData} />
                  </div>
                </div>

                {/* Related conflict events — cross-links to the Intelligence Graph page */}
                {relatedConflicts.length > 0 && (
                  <div className="border p-4" style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)' }}>
                    <div className="text-sm mb-3" style={{ color: 'var(--text-dim)' }}>Related conflict events</div>
                    <div className="flex gap-3 overflow-x-auto pb-1">
                      {relatedConflicts.map((c) => (
                        <button
                          key={c.id}
                          onClick={() => onJumpToGraph?.(c.name, 'conflict')}
                          className="font-mono-data shrink-0 whitespace-nowrap border px-3 py-2 text-xs transition-colors hover:border-[var(--accent)]"
                          style={{ borderColor: 'var(--hairline)', color: 'var(--text)', background: 'var(--panel)' }}
                        >
                          {c.name}
                        </button>
                      ))}
                    </div>
                  </div>
                )}

                {/* News */}
                <div className="border p-4" style={{ background: 'var(--panel-2)', borderColor: 'var(--hairline)' }}>
                  <div className="text-sm mb-3" style={{ color: 'var(--text-dim)' }}>Latest news</div>
                  <div className="space-y-3">
                    {lastNews.length === 0 && (
                      <div className="text-sm" style={{ color: 'var(--text-faint)' }}>No news available.</div>
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
                          className="block border p-3 transition-colors hover:border-[var(--accent)]"
                          style={{ borderColor: 'var(--hairline)' }}
                          onClick={(e) => { if (!url) e.preventDefault(); }}
                        >
                          <div className="flex gap-3">
                            {image ? (
                              <img src={image} alt="news" className="w-14 h-14 object-cover shrink-0" />
                            ) : (
                              <div className="w-14 h-14 shrink-0 flex items-center justify-center text-xs" style={{ background: 'var(--panel)', color: 'var(--text-faint)' }}>
                                N/A
                              </div>
                            )}
                            <div className="min-w-0">
                              <div className="font-semibold text-sm line-clamp-2" style={{ color: 'var(--text)' }}>{n?.headline || '—'}</div>
                              <div className="text-xs mt-1" style={{ color: 'var(--text-faint)' }}>{n?.source || '—'}</div>
                              <div className="text-xs mt-1" style={{ color: 'var(--text-dim)' }}>{datetime}</div>
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