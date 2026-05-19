import React, { useMemo, useState } from 'react';
import Map, { Marker, Popup, Source, Layer } from 'react-map-gl/maplibre';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useTelemetryStore } from '../store/telemetryStore';
import { useTelemetrySocket } from '../hooks/useTelemetrySocket';

const OLA_MAPS_API_KEY = import.meta.env.VITE_OLA_MAPS_API_KEY;

export const RadarView = () => {
  // Initialize the WebSocket connection
  useTelemetrySocket();
  
  // Read from the Zustand store
  const flights = useTelemetryStore((state) => state.flights);

  // MapLibre requires a transformRequest to inject the API key into tile requests
  const transformRequest = useMemo(() => {
    return (url, resourceType) => {
      // Avoid appending if it already has the key (though rare)
      if (url.includes('api_key=')) {
        return { url };
      }
      const separator = url.includes('?') ? '&' : '?';
      return { url: `${url}${separator}api_key=${OLA_MAPS_API_KEY}` };
    };
  }, []);

  // Track active popup state (MapLibre popups don't auto-bind to markers like Leaflet)
  const [selectedFlight, setSelectedFlight] = useState(null);

  return (
    <div className="flex flex-col h-full bg-slate-900 rounded-lg shadow-lg border border-slate-700 p-6 overflow-hidden">
      <div className="flex justify-between items-center mb-4 border-b border-slate-700 pb-2">
        <h2 className="text-2xl font-bold text-emerald-400">Tactical Radar Map (Vector)</h2>
        <div className="text-sm text-slate-400">
          <span className="inline-block w-2 h-2 rounded-full bg-emerald-500 animate-pulse mr-2"></span>
          {flights.length} Active Tracks
        </div>
      </div>
      
      <div className="flex-grow rounded border border-slate-800 overflow-hidden relative z-0">
        <Map
          initialViewState={{
            longitude: -115.0,
            latitude: 35.0,
            zoom: 4
          }}
          style={{ width: '100%', height: '100%' }}
          mapStyle={`https://api.olamaps.io/tiles/vector/v1/styles/default-dark-standard/style.json`}
          transformRequest={transformRequest}
          mapLib={maplibregl}
        >
          <Source id="countries" type="geojson" data="https://raw.githubusercontent.com/datasets/geo-countries/master/data/countries.geojson">
            <Layer 
              id="countries-layer" 
              type="line" 
              paint={{'line-color': 'rgba(255,255,255,0.15)', 'line-width': 1}} 
              filter={['!=', ['get', 'ISO_A3'], 'IND']} 
            />
          </Source>

          {flights.map((flight) => (
            <Marker 
              key={flight.callsign} 
              longitude={flight.longitude} 
              latitude={flight.latitude}
              anchor="center"
              onClick={e => {
                // Prevent map click from firing
                e.originalEvent.stopPropagation();
                setSelectedFlight(flight);
              }}
            >
              <div 
                className="w-3 h-3 bg-emerald-500 rounded-full border-2 border-white shadow-[0_0_10px_#10b981] cursor-pointer hover:bg-emerald-400 transition-colors"
              />
            </Marker>
          ))}

          {selectedFlight && (
            <Popup
              longitude={selectedFlight.longitude}
              latitude={selectedFlight.latitude}
              anchor="bottom"
              offset={10}
              onClose={() => setSelectedFlight(null)}
              closeOnClick={false}
              className="custom-popup"
            >
              <div className="text-slate-800 font-sans p-1">
                <h3 className="font-bold text-lg border-b pb-1 mb-2 text-emerald-600">{selectedFlight.callsign}</h3>
                <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                  <span className="text-slate-500">Altitude:</span>
                  <span className="font-mono font-medium">{Math.round(selectedFlight.altitude)} ft</span>
                  
                  <span className="text-slate-500">Heading:</span>
                  <span className="font-mono font-medium">{Math.round(selectedFlight.heading)}&deg;</span>
                  
                  <span className="text-slate-500">Lat/Lon:</span>
                  <span className="font-mono font-medium text-xs">
                    {selectedFlight.latitude.toFixed(4)}, {selectedFlight.longitude.toFixed(4)}
                  </span>
                </div>
              </div>
            </Popup>
          )}
        </Map>
      </div>
    </div>
  );
};
