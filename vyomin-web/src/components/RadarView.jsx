import React, { useMemo, useState, useCallback } from 'react';
import Map, { Marker, Popup } from 'react-map-gl/maplibre';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useTelemetryStore } from '../store/telemetryStore';
import { useTelemetrySocket } from '../hooks/useTelemetrySocket';

const OLA_MAPS_API_KEY = import.meta.env.VITE_OLA_MAPS_API_KEY;

// ── Flight type configuration ──────────────────────────────────────────────
const FLIGHT_TYPES = {
  MILITARY:   { label: 'Military',    color: '#f43f5e', glow: '#f43f5e', icon: '✈' },
  COMMERCIAL: { label: 'Commercial',  color: '#10b981', glow: '#10b981', icon: '✈' },
  CARGO:      { label: 'Cargo',       color: '#f59e0b', glow: '#f59e0b', icon: '✈' },
  HELICOPTER: { label: 'Helicopter',  color: '#a78bfa', glow: '#a78bfa', icon: '🚁' },
  DRONE:      { label: 'Drone',       color: '#06b6d4', glow: '#06b6d4', icon: '⬡' },
  PRIVATE:    { label: 'Private',     color: '#94a3b8', glow: '#94a3b8', icon: '✈' },
  UNKNOWN:    { label: 'Unknown',     color: '#475569', glow: '#475569', icon: '?' },
};

const ALL_TYPES = Object.keys(FLIGHT_TYPES);

export const RadarView = () => {
  useTelemetrySocket();
  const flights = useTelemetryStore((state) => state.flights);

  // Default: show only MILITARY so the map isn't overloaded on first load
  const [activeTypes, setActiveTypes] = useState(new Set(['MILITARY']));
  const [selectedFlight, setSelectedFlight] = useState(null);

  const transformRequest = useMemo(() => {
    return (url) => {
      if (url.includes('api_key=')) return { url };
      const sep = url.includes('?') ? '&' : '?';
      return { url: `${url}${sep}api_key=${OLA_MAPS_API_KEY}` };
    };
  }, []);

  // Toggle a single filter type
  const toggleType = useCallback((type) => {
    setActiveTypes((prev) => {
      const next = new Set(prev);
      if (next.has(type)) {
        // Always keep at least one type active
        if (next.size > 1) next.delete(type);
      } else {
        next.add(type);
      }
      return next;
    });
  }, []);

  // Quick presets
  const selectAll  = () => setActiveTypes(new Set(ALL_TYPES));
  const selectOnly = (type) => setActiveTypes(new Set([type]));

  // Filter flights by active types
  const visibleFlights = useMemo(
    () => flights.filter((f) => activeTypes.has(f.flightType ?? 'UNKNOWN')),
    [flights, activeTypes]
  );

  // Count per type across ALL received flights
  const typeCounts = useMemo(() => {
    const counts = {};
    for (const t of ALL_TYPES) counts[t] = 0;
    for (const f of flights) {
      const t = f.flightType ?? 'UNKNOWN';
      if (counts[t] !== undefined) counts[t]++;
    }
    return counts;
  }, [flights]);

  return (
    <div className="flex flex-col h-full bg-slate-900 rounded-lg shadow-lg border border-slate-700 overflow-hidden">

      {/* ── Header ─────────────────────────────────────────────────────────── */}
      <div className="flex justify-between items-center px-5 py-3 border-b border-slate-700">
        <h2 className="text-xl font-bold text-emerald-400 tracking-wide">
          Tactical Radar Map
        </h2>
        <div className="flex items-center gap-3 text-sm text-slate-400">
          <span className="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
          <span>
            <span className="text-white font-semibold">{visibleFlights.length}</span>
            {' '}/ {flights.length} tracks visible
          </span>
        </div>
      </div>

      {/* ── Filter bar ─────────────────────────────────────────────────────── */}
      <div className="flex items-center gap-2 px-5 py-2 bg-slate-800/60 border-b border-slate-700 flex-wrap">
        <span className="text-xs text-slate-500 uppercase tracking-widest mr-1">Filter</span>

        {ALL_TYPES.map((type) => {
          const cfg     = FLIGHT_TYPES[type];
          const active  = activeTypes.has(type);
          const count   = typeCounts[type];
          return (
            <button
              key={type}
              onClick={() => toggleType(type)}
              onDoubleClick={() => selectOnly(type)}
              title={`Double-click to show only ${cfg.label}`}
              style={{
                borderColor: active ? cfg.color : 'transparent',
                color:       active ? cfg.color : '#64748b',
                boxShadow:   active ? `0 0 8px ${cfg.glow}55` : 'none',
                background:  active ? `${cfg.color}18` : 'transparent',
              }}
              className="flex items-center gap-1.5 text-xs font-semibold px-3 py-1 rounded-full border transition-all duration-200 hover:opacity-90 cursor-pointer select-none"
            >
              <span>{cfg.icon}</span>
              <span>{cfg.label}</span>
              {count > 0 && (
                <span
                  className="text-[10px] rounded-full px-1.5 py-0 font-mono"
                  style={{ background: active ? `${cfg.color}30` : '#1e293b', color: active ? cfg.color : '#475569' }}
                >
                  {count}
                </span>
              )}
            </button>
          );
        })}

        {/* All / None shortcuts */}
        <div className="ml-auto flex gap-2">
          <button
            onClick={selectAll}
            className="text-xs text-slate-400 hover:text-slate-200 px-2 py-1 rounded border border-slate-600 hover:border-slate-400 transition-colors"
          >
            All
          </button>
        </div>
      </div>

      {/* ── Map ────────────────────────────────────────────────────────────── */}
      <div className="flex-grow relative z-0">
        <Map
          initialViewState={{ longitude: 20.0, latitude: 30.0, zoom: 2 }}
          style={{ width: '100%', height: '100%' }}
          mapStyle={import.meta.env.VITE_OLA_MAPS_STYLE_URL || "https://api.olamaps.io/tiles/vector/v1/styles/default-dark-standard/style.json"}
          transformRequest={transformRequest}
          mapLib={maplibregl}
        >
          {visibleFlights.map((flight) => {
            const type = flight.flightType ?? 'UNKNOWN';
            const cfg  = FLIGHT_TYPES[type] ?? FLIGHT_TYPES.UNKNOWN;
            return (
              <Marker
                key={flight.callsign}
                longitude={flight.longitude}
                latitude={flight.latitude}
                anchor="center"
                onClick={(e) => {
                  e.originalEvent.stopPropagation();
                  setSelectedFlight(flight);
                }}
              >
                <div
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: '50%',
                    background: cfg.color,
                    border: '1.5px solid rgba(255,255,255,0.6)',
                    boxShadow: `0 0 8px ${cfg.glow}`,
                    cursor: 'pointer',
                    transition: 'transform 0.15s',
                  }}
                  onMouseEnter={(e) => (e.currentTarget.style.transform = 'scale(1.6)')}
                  onMouseLeave={(e) => (e.currentTarget.style.transform = 'scale(1)')}
                />
              </Marker>
            );
          })}

          {selectedFlight && (() => {
            const type = selectedFlight.flightType ?? 'UNKNOWN';
            const cfg  = FLIGHT_TYPES[type] ?? FLIGHT_TYPES.UNKNOWN;
            return (
              <Popup
                longitude={selectedFlight.longitude}
                latitude={selectedFlight.latitude}
                anchor="bottom"
                offset={12}
                onClose={() => setSelectedFlight(null)}
                closeOnClick={false}
              >
                <div className="font-sans p-1 min-w-[160px]">
                  <div className="flex items-center justify-between border-b pb-1 mb-2">
                    <h3 className="font-bold text-base" style={{ color: cfg.color }}>
                      {selectedFlight.callsign}
                    </h3>
                    <span
                      className="text-[10px] font-semibold px-2 py-0.5 rounded-full ml-2"
                      style={{ background: `${cfg.color}25`, color: cfg.color }}
                    >
                      {cfg.label}
                    </span>
                  </div>
                  <div className="grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-slate-600 mb-2">
                    <span className="text-slate-400">Altitude</span>
                    <span className="font-mono font-medium text-slate-800">
                      {Math.round(selectedFlight.altitude)} m
                    </span>
                    <span className="text-slate-400">Heading</span>
                    <span className="font-mono font-medium text-slate-800">
                      {Math.round(selectedFlight.heading)}°
                    </span>
                    <span className="text-slate-400">Lat / Lon</span>
                    <span className="font-mono text-[10px] text-slate-700">
                      {selectedFlight.latitude.toFixed(3)}, {selectedFlight.longitude.toFixed(3)}
                    </span>
                  </div>
                  {/* Aircraft Details from DB */}
                  <div className="pt-2 border-t border-slate-200">
                    <div className="text-[10px] uppercase tracking-wider text-slate-400 mb-1">Aircraft Details</div>
                    <div className="text-xs font-semibold text-slate-700 leading-tight">
                      {selectedFlight.aircraftModel || 'Unknown Model'}
                    </div>
                    {selectedFlight.registration && (
                      <div className="text-[10px] font-mono text-slate-500 mt-0.5">
                        Reg: {selectedFlight.registration}
                      </div>
                    )}
                  </div>
                </div>
              </Popup>
            );
          })()}
        </Map>
      </div>
    </div>
  );
};
