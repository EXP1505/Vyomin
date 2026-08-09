import { useEffect, useRef, useState } from 'react';
import { regionLabel } from './regionLabel';

const MAX_ROWS = 14;

function formatClock(date) {
  return date.toTimeString().slice(0, 8);
}

function typeStyle(type) {
  switch (type) {
    case 'CONFLICT':
      return { label: 'CONFLICT', color: 'var(--accent)' };
    case 'MARKET_UP':
      return { label: 'MARKET', color: 'var(--positive)' };
    case 'MARKET_DOWN':
      return { label: 'MARKET', color: 'var(--negative)' };
    case 'RADAR':
      return { label: 'RADAR', color: '#5b8dee' };
    default:
      return { label: type, color: 'var(--text-dim)' };
  }
}

// Merges three already-live data sources (conflicts poll, finance poll, telemetry websocket)
// into one time-ordered feed. First pass just seeds the visible rows from whatever's already
// loaded; every pass after that only animates in rows keyed by something genuinely new
// (conflict id, ticker % change, or a callsign never seen this session) so a bulk refresh of
// any one source doesn't dump hundreds of rows in at once.
export function LiveSignalFeed({ conflicts = [], tickers = [], flights = [] }) {
  const [rows, setRows] = useState([]);
  const seenConflicts = useRef(new Set());
  const seenFlights = useRef(new Set());
  const seenTickers = useRef(new Map());
  const initialized = useRef(false);

  useEffect(() => {
    if (!initialized.current) {
      if (conflicts.length === 0 && tickers.length === 0 && flights.length === 0) return;

      const seed = [];
      conflicts.slice(0, 4).forEach((c) => {
        const key = c.id ?? c.gdeltEventId;
        if (key == null) return;
        seenConflicts.current.add(key);
        seed.push({ id: `c-${key}`, ts: new Date(), type: 'CONFLICT', text: c.name });
      });
      flights.slice(0, 3).forEach((f) => {
        if (!f.callsign) return;
        seenFlights.current.add(f.callsign);
        const label = f.flightType || 'Unknown';
        seed.push({
          id: `r-${f.callsign}`,
          ts: new Date(),
          type: 'RADAR',
          text: `${label} track over ${regionLabel(f.latitude, f.longitude)}`,
        });
      });
      tickers.forEach((t) => seenTickers.current.set(t.symbol, t.percentChange));

      setRows(seed.slice(0, MAX_ROWS));
      initialized.current = true;
      return;
    }

    const additions = [];

    for (const c of conflicts.slice(0, 20)) {
      const key = c.id ?? c.gdeltEventId;
      if (key == null || seenConflicts.current.has(key)) continue;
      seenConflicts.current.add(key);
      additions.push({ id: `c-${key}`, ts: new Date(), type: 'CONFLICT', text: c.name });
    }

    for (const t of tickers) {
      const prevPct = seenTickers.current.get(t.symbol);
      if (prevPct === undefined) {
        seenTickers.current.set(t.symbol, t.percentChange);
        continue;
      }
      if (prevPct === t.percentChange) continue;
      seenTickers.current.set(t.symbol, t.percentChange);
      const pct = typeof t.percentChange === 'number' ? t.percentChange : 0;
      const sign = pct >= 0 ? '+' : '';
      additions.push({
        id: `m-${t.symbol}-${Date.now()}`,
        ts: new Date(),
        type: pct >= 0 ? 'MARKET_UP' : 'MARKET_DOWN',
        text: `${t.symbol} ${sign}${pct.toFixed(2)}% — live quote`,
      });
    }

    for (const f of flights) {
      if (!f.callsign || seenFlights.current.has(f.callsign)) continue;
      seenFlights.current.add(f.callsign);
      const label = f.flightType || 'Unknown';
      additions.push({
        id: `r-${f.callsign}`,
        ts: new Date(),
        type: 'RADAR',
        text: `${label} track entered ${regionLabel(f.latitude, f.longitude)}`,
      });
    }

    if (additions.length === 0) return;
    setRows((prev) => [...additions, ...prev].slice(0, MAX_ROWS));
  }, [conflicts, tickers, flights]);

  return (
    <div className="flex h-full flex-col gap-1.5 overflow-y-auto pr-1">
      {rows.length === 0 && (
        <div className="text-xs" style={{ color: 'var(--text-faint)' }}>
          Awaiting live signal…
        </div>
      )}
      {rows.map((row, i) => {
        const style = typeStyle(row.type);
        return (
          <div
            key={row.id}
            className="flex items-baseline gap-2 border-b pb-1.5 text-xs"
            style={{
              borderColor: 'var(--hairline)',
              animation: i === 0 ? 'vyomin-feed-in 0.4s ease-out' : undefined,
            }}
          >
            <span className="font-mono-data shrink-0" style={{ color: 'var(--text-faint)' }}>
              {formatClock(row.ts)}
            </span>
            <span className="font-mono-data shrink-0 tracking-wider" style={{ color: style.color, width: '4.5rem' }}>
              {style.label}
            </span>
            <span className="truncate" style={{ color: 'var(--text-dim)' }}>
              {row.text}
            </span>
          </div>
        );
      })}
      <style>{`
        @keyframes vyomin-feed-in {
          0% { opacity: 0; transform: translateY(-6px); }
          100% { opacity: 1; transform: translateY(0); }
        }
      `}</style>
    </div>
  );
}
