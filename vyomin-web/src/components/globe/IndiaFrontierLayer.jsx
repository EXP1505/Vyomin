import { useEffect, useState } from 'react';
import { Line } from '@react-three/drei';
import { latLonToVector3, polygonRings } from './geo';
import soiBoundary from '../../data/india-frontier-soi.geojson?raw';

// Kashmir, Aksai Chin, and Arunachal Pradesh are internationally disputed — different
// sources draw this frontier differently (India / Pakistan / China / standard
// international datasets). This layer is deliberately separate from the base
// CountryBorders dataset (which excludes India entirely) so the boundary source can be
// swapped without touching any other map logic.
//
// Default source: Survey of India State Map boundary, via DataMeet's community GeoJSON
// (https://github.com/datameet/maps/tree/master/Country, india-soi.geojson, CC-BY-SA/ODbL),
// simplified from ~282k to ~3.4k points for the globe. This traces the official Indian
// government line — Jammu & Kashmir, Aksai Chin, and Arunachal Pradesh as part of India.
// Pass `boundaryDataUrl` to override with a different/updated GeoJSON without code changes.
const DEFAULT_BOUNDARY = JSON.parse(soiBoundary);

export function IndiaFrontierLayer({ boundaryDataUrl, onClick }) {
  const [rings, setRings] = useState(() => polygonRings(DEFAULT_BOUNDARY.features?.[0]?.geometry));

  useEffect(() => {
    if (!boundaryDataUrl) return;
    let cancelled = false;
    fetch(boundaryDataUrl)
      .then((r) => r.json())
      .then((geojson) => {
        if (cancelled) return;
        const feature = geojson.features?.[0];
        setRings(polygonRings(feature?.geometry));
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [boundaryDataUrl]);

  return (
    <group onClick={(e) => { e.stopPropagation(); onClick?.(); }}>
      {rings.map((ring, i) => (
        <Line
          key={i}
          points={ring.map(([lat, lon]) => latLonToVector3(lat, lon, 2.002))}
          color="#ffb020"
          lineWidth={1}
          transparent
          opacity={0.65}
        />
      ))}
    </group>
  );
}
