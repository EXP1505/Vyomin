import { useMemo, useRef } from 'react';
import { useFrame } from '@react-three/fiber';
import { latLonToVector3 } from './geo';
import { getRingTexture } from './markerTextures';

// Capped like TrackMarkers' filter-before-render approach - GDELT's recent-conflicts feed can
// return hundreds of rows, and one GPU sprite per row is cheap, but there's no reason to draw
// more flashpoints than are visually distinguishable on a globe.
const MAX_MARKERS = 150;

export function ConflictMarkers({ conflicts = [] }) {
  const groupRef = useRef();
  const texture = useMemo(() => getRingTexture('#ffb020'), []);

  const points = useMemo(
    () =>
      conflicts
        .filter((c) => Number.isFinite(c.latitude) && Number.isFinite(c.longitude))
        .slice(0, MAX_MARKERS)
        .map((c, i) => ({
          id: c.id ?? c.gdeltEventId ?? i,
          position: latLonToVector3(c.latitude, c.longitude, 2.015),
          phase: (i * 0.37) % (Math.PI * 2),
        })),
    [conflicts]
  );

  useFrame(({ clock }) => {
    const group = groupRef.current;
    if (!group) return;
    const t = clock.getElapsedTime();
    group.children.forEach((sprite, i) => {
      const p = points[i];
      if (!p) return;
      const pulse = (Math.sin(t * 1.6 + p.phase) + 1) / 2; // 0..1
      const scale = 0.1 + pulse * 0.1;
      sprite.scale.set(scale, scale, 1);
      sprite.material.opacity = 0.25 + pulse * 0.55;
    });
  });

  return (
    <group ref={groupRef}>
      {points.map((p) => (
        <sprite key={p.id} position={p.position}>
          <spriteMaterial map={texture} transparent depthWrite={false} />
        </sprite>
      ))}
    </group>
  );
}
