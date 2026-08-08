import { useMemo, useState } from 'react';
import { useThree, useFrame } from '@react-three/fiber';
import * as THREE from 'three';
import { latLonToVector3, GLOBE_RADIUS } from './geo';
import { getLabelTexture } from './labelTextures';
import capitals from '../../data/world-capitals.json';

const LABEL_VISIBLE_DISTANCE = 3.0;
const LABEL_HEIGHT = 0.11;

export function CapitalMarkers() {
  const { camera } = useThree();
  const points = useMemo(
    () =>
      capitals.map((c) => {
        const position = latLonToVector3(c.lat, c.lon, 2.02);
        const normal = new THREE.Vector3(...position).normalize();
        return { ...c, position, normal };
      }),
    []
  );

  const [visible, setVisible] = useState(false);
  useFrame(() => {
    const dist = camera.position.length();
    const next = dist < LABEL_VISIBLE_DISTANCE;
    if (next !== visible) setVisible(next);
  });

  if (!visible) return null;

  // Depth-tested sprites naturally get occluded by the opaque globe mesh, so only the
  // near-hemisphere labels are visible — no manual front/back bookkeeping needed.
  return (
    <group>
      {points.map((c) => {
        const { texture, aspect } = getLabelTexture(c.city);
        return (
          <group key={c.city}>
            <mesh position={c.position}>
              <sphereGeometry args={[GLOBE_RADIUS * 0.0035, 6, 6]} />
              <meshBasicMaterial color="#7c8698" />
            </mesh>
            <sprite position={[c.position[0], c.position[1] + LABEL_HEIGHT * 0.6, c.position[2]]} scale={[LABEL_HEIGHT * aspect, LABEL_HEIGHT, 1]}>
              <spriteMaterial map={texture} transparent depthTest sizeAttenuation />
            </sprite>
          </group>
        );
      })}
    </group>
  );
}
