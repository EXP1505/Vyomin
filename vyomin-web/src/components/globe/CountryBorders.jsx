import { useMemo } from 'react';
import countriesTopo from 'world-atlas/countries-110m.json';
import { latLonToVector3, polygonRings, topoToCountryFeatures } from './geo';

// India is rendered by the separate IndiaFrontierLayer overlay instead, since
// its frontier is disputed and needs to be swappable independent of the base dataset.
const INDIA_NAME = 'India';

export function CountryBorders() {
  const features = useMemo(() => topoToCountryFeatures(countriesTopo), []);

  // All rings flattened into a single point list so the whole world's borders are one
  // draw call instead of one <Line> per ring per country (~250 draw calls was a big
  // chunk of the frame-rate hit alongside the marker DOM nodes).
  const allPoints = useMemo(() => {
    const points = [];
    for (const f of features) {
      if (f.properties?.name === INDIA_NAME) continue;
      for (const ring of polygonRings(f.geometry)) {
        for (let i = 0; i < ring.length - 1; i++) {
          points.push(latLonToVector3(ring[i][0], ring[i][1], 2.001));
          points.push(latLonToVector3(ring[i + 1][0], ring[i + 1][1], 2.001));
        }
      }
    }
    return points;
  }, [features]);

  return (
    <group>
      <lineSegments>
        <bufferGeometry>
          <bufferAttribute
            attach="attributes-position"
            args={[new Float32Array(allPoints.flatMap((p) => p)), 3]}
          />
        </bufferGeometry>
        <lineBasicMaterial color="#2c323f" transparent opacity={0.6} />
      </lineSegments>
    </group>
  );
}
