import { useMemo, useState, useCallback, useRef } from 'react';
import { useTelemetryStore } from '../store/telemetryStore';
import { useTelemetrySocket } from '../hooks/useTelemetrySocket';
import { Globe } from './globe/Globe';
import { TrackDetailPanel } from './globe/TrackDetailPanel';
import { Panel } from './design/Panel';
import { TRACK_ICONS } from './design/trackIcons';

// Commercial airline traffic is filtered out server-side (FlightTelemetryService) - it's the
// bulk of OpenSky's global states and isn't a tactical target, so there's no filter for it here.
const FLIGHT_TYPES = {
  MILITARY: { label: 'Military', color: '#ffb020' },
  CARGO: { label: 'Cargo', color: '#7c8698' },
  HELICOPTER: { label: 'Helicopter', color: '#9aa4b5' },
  DRONE: { label: 'Drone', color: '#9aa4b5' },
  PRIVATE: { label: 'Private', color: '#7c8698' },
  UNKNOWN: { label: 'Unknown', color: '#454c5c' },
};

const ALL_TYPES = Object.keys(FLIGHT_TYPES);

export const RadarView = () => {
  useTelemetrySocket();
  const flights = useTelemetryStore((state) => state.flights);

  // Default: show only MILITARY so the globe isn't overloaded on first load
  const [activeTypes, setActiveTypes] = useState(new Set(['MILITARY']));
  const [selectedFlight, setSelectedFlight] = useState(null);
  const globeRef = useRef(null);

  const toggleType = useCallback((type) => {
    setActiveTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        if (next.size > 1) next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  }, []);

  const selectAll = () => setActiveTypes(new Set(ALL_TYPES));
  const selectOnly = (type) => setActiveTypes(new Set([type]));

  const typeCounts = useMemo(() => {
    const counts = {};
    for (const t of ALL_TYPES) counts[t] = 0;
    for (const f of flights) {
      const t = f.flightType ?? 'UNKNOWN';
      if (counts[t] !== undefined) counts[t]++;
    }
    return counts;
  }, [flights]);

  const visibleCount = useMemo(
    () => flights.filter((f) => activeTypes.has(f.flightType ?? 'UNKNOWN')).length,
    [flights, activeTypes]
  );

  return (
    <Panel className="flex h-full flex-col overflow-hidden">
      <div className="flex items-center justify-between px-5 py-3 border-b" style={{ borderColor: 'var(--hairline)' }}>
        <h2 className="text-lg font-semibold tracking-wide" style={{ color: 'var(--text)' }}>
          Tactical Radar — Global Track View
        </h2>
        <div className="font-mono-data flex items-center gap-2 text-xs" style={{ color: 'var(--text-dim)' }}>
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--positive)' }} />
          <span style={{ color: 'var(--text)' }}>{visibleCount}</span>/{flights.length} TRACKS VISIBLE
        </div>
      </div>

      <div
        className="flex flex-wrap items-center gap-2 px-5 py-2.5 border-b"
        style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}
      >
        <span className="text-[10px] uppercase tracking-widest mr-1" style={{ color: 'var(--text-faint)' }}>
          Filter
        </span>
        {ALL_TYPES.map((type) => {
          const cfg = FLIGHT_TYPES[type];
          const active = activeTypes.has(type);
          const count = typeCounts[type];
          const Icon = TRACK_ICONS[type];
          return (
            <button
              key={type}
              onClick={() => toggleType(type)}
              onDoubleClick={() => selectOnly(type)}
              title={`Double-click to show only ${cfg.label}`}
              className="flex items-center gap-1.5 border px-3 py-1 text-xs font-mono-data transition-all duration-150 select-none"
              style={{
                borderColor: active ? cfg.color : 'var(--hairline)',
                color: active ? cfg.color : 'var(--text-faint)',
                background: active ? `${cfg.color}18` : 'transparent',
              }}
            >
              <Icon size={13} color={active ? cfg.color : 'var(--text-faint)'} />
              <span>{cfg.label}</span>
              {count > 0 && <span style={{ opacity: 0.7 }}>{count}</span>}
            </button>
          );
        })}
        <button
          onClick={selectAll}
          className="ml-auto border px-3 py-1 text-xs transition-colors"
          style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
        >
          All
        </button>
      </div>

      <div className="relative flex-grow">
        <Globe
          ref={globeRef}
          flights={flights}
          activeTypes={activeTypes}
          selectedCallsign={selectedFlight?.callsign}
          onSelectTrack={setSelectedFlight}
        />
        <div className="absolute bottom-5 right-5 flex flex-col border" style={{ borderColor: 'var(--hairline)', background: 'var(--panel)' }}>
          <button
            onClick={() => globeRef.current?.zoomIn()}
            className="flex h-9 w-9 items-center justify-center text-lg border-b transition-colors hover:text-[var(--accent)]"
            style={{ borderColor: 'var(--hairline)', color: 'var(--text-dim)' }}
            title="Zoom in"
          >
            +
          </button>
          <button
            onClick={() => globeRef.current?.zoomOut()}
            className="flex h-9 w-9 items-center justify-center text-lg transition-colors hover:text-[var(--accent)]"
            style={{ color: 'var(--text-dim)' }}
            title="Zoom out"
          >
            −
          </button>
        </div>
        <TrackDetailPanel flight={selectedFlight} onClose={() => setSelectedFlight(null)} />
      </div>
    </Panel>
  );
};
