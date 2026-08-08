import { useMemo } from 'react';
import { latLonToVector3 } from './geo';
import { getMarkerTexture } from './markerTextures';

const TYPE_COLORS = {
  MILITARY: '#ffb020',
  CARGO: '#8fa3c2',
  HELICOPTER: '#c7ccd6',
  DRONE: '#6fd6c9',
  PRIVATE: '#9aa4b5',
  UNKNOWN: '#5b6373',
};

// Rendered as plain THREE.Sprite objects (GPU-billboarded, no DOM) so this scales
// to thousands of live tracks — an <Html> marker per track would create that many
// real DOM nodes with a CSS3D transform recomputed every frame, which is what made
// the globe crawl before.
export function TrackMarkers({ flights, activeTypes, selectedCallsign, onSelect }) {
  const positioned = useMemo(
    () =>
      flights
        .filter((f) => activeTypes.has(f.flightType || 'UNKNOWN'))
        .map((f) => ({
          ...f,
          position: latLonToVector3(f.latitude, f.longitude, 2.03),
        })),
    [flights, activeTypes]
  );

  return (
    <group>
      {positioned.map((f) => {
        const type = f.flightType || 'UNKNOWN';
        const color = TYPE_COLORS[type] || TYPE_COLORS.UNKNOWN;
        const texture = getMarkerTexture(type, color);
        const isSelected = f.callsign === selectedCallsign;
        const scale = isSelected ? 0.17 : 0.11;

        return (
          <sprite
            key={f.callsign}
            position={f.position}
            scale={[scale, scale, 1]}
            onClick={(e) => {
              e.stopPropagation();
              onSelect?.(f);
            }}
          >
            <spriteMaterial map={texture} rotation={((f.heading || 0) * Math.PI) / 180} transparent />
          </sprite>
        );
      })}
    </group>
  );
}
