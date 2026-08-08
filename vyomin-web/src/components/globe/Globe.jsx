import { Suspense, useRef, useState, useCallback, forwardRef, useImperativeHandle } from 'react';
import { Canvas, useFrame, useThree } from '@react-three/fiber';
import { OrbitControls } from '@react-three/drei';
import * as THREE from 'three';
import { CountryBorders } from './CountryBorders';
import { IndiaFrontierLayer } from './IndiaFrontierLayer';
import { TrackMarkers } from './TrackMarkers';
import { CapitalMarkers } from './CapitalMarkers';
import { latLonToVector3, GLOBE_RADIUS } from './geo';

const IDLE_RESUME_MS = 4000;

// Once the camera arrives at a clicked target it hands control back to OrbitControls
// via onArrive — without this the lerp never stops and fights the user's own
// zoom/orbit input forever, which is what made zooming feel "uncontrollable".
function CameraFocus({ target, onArrive }) {
  const { camera, controls } = useThree();
  useFrame(() => {
    if (!target) return;
    const desired = new THREE.Vector3(...target).normalize().multiplyScalar(3.4);
    camera.position.lerp(desired, 0.08);
    controls?.target?.lerp?.(new THREE.Vector3(0, 0, 0), 0.08);
    camera.updateProjectionMatrix();
    if (camera.position.distanceTo(desired) < 0.02) {
      onArrive?.();
    }
  });
  return null;
}

const MIN_DISTANCE = 2.6;
const MAX_DISTANCE = 8;

// Bridges the imperative zoom buttons (rendered outside the Canvas as regular HTML)
// to the camera living inside it.
const ZoomController = forwardRef((_props, ref) => {
  const { camera, controls } = useThree();
  useImperativeHandle(ref, () => ({
    zoomIn: () => {
      const dist = Math.max(MIN_DISTANCE, camera.position.length() * 0.8);
      camera.position.setLength(dist);
      controls?.update();
    },
    zoomOut: () => {
      const dist = Math.min(MAX_DISTANCE, camera.position.length() * 1.25);
      camera.position.setLength(dist);
      controls?.update();
    },
  }));
  return null;
});

function Scene({ flights, activeTypes, selectedCallsign, onSelectTrack, onCountryClick, focusTarget, onArrive, zoomRef, compact }) {
  return (
    <>
      <ambientLight intensity={1.1} />
      <pointLight position={[5, 3, 5]} intensity={0.4} />
      <mesh
        onClick={(e) => {
          e.stopPropagation();
          onCountryClick?.(e.point);
        }}
      >
        <sphereGeometry args={[GLOBE_RADIUS, 48, 48]} />
        <meshBasicMaterial color="#0d0f14" transparent opacity={0.98} />
      </mesh>
      <CountryBorders />
      <IndiaFrontierLayer />
      {!compact && (
        <>
          <TrackMarkers
            flights={flights}
            activeTypes={activeTypes}
            selectedCallsign={selectedCallsign}
            onSelect={onSelectTrack}
          />
          <CapitalMarkers />
        </>
      )}
      <CameraFocus target={focusTarget} onArrive={onArrive} />
      {!compact && <ZoomController ref={zoomRef} />}
    </>
  );
}

export const Globe = forwardRef(function Globe(
  { flights = [], activeTypes = new Set(), selectedCallsign, onSelectTrack, compact = false },
  ref
) {
  const controlsRef = useRef();
  const zoomRef = useRef();
  const idleTimer = useRef();
  const [autoRotate, setAutoRotate] = useState(true);
  const [focusTarget, setFocusTarget] = useState(null);

  useImperativeHandle(ref, () => ({
    zoomIn: () => zoomRef.current?.zoomIn(),
    zoomOut: () => zoomRef.current?.zoomOut(),
  }));

  const pauseAutoRotate = useCallback(() => {
    setAutoRotate(false);
    clearTimeout(idleTimer.current);
    idleTimer.current = setTimeout(() => setAutoRotate(true), IDLE_RESUME_MS);
  }, []);

  const handleCountryClick = useCallback((point) => {
    if (!point) return;
    setFocusTarget([point.x, point.y, point.z].map((v) => v * (3.4 / GLOBE_RADIUS)));
  }, []);

  const handleSelectTrack = useCallback(
    (flight) => {
      setFocusTarget(latLonToVector3(flight.latitude, flight.longitude, 3.4));
      onSelectTrack?.(flight);
    },
    [onSelectTrack]
  );

  return (
    <Canvas
      camera={{ position: [0, 0, 5.5], fov: 45 }}
      onPointerDown={pauseAutoRotate}
      onWheel={pauseAutoRotate}
    >
      <Suspense fallback={null}>
        <Scene
          flights={flights}
          activeTypes={activeTypes}
          selectedCallsign={selectedCallsign}
          onSelectTrack={handleSelectTrack}
          onCountryClick={handleCountryClick}
          focusTarget={focusTarget}
          onArrive={() => setFocusTarget(null)}
          zoomRef={zoomRef}
          compact={compact}
        />
      </Suspense>
      {!compact && (
        <OrbitControls
          ref={controlsRef}
          makeDefault
          enablePan={false}
          minDistance={MIN_DISTANCE}
          maxDistance={MAX_DISTANCE}
          zoomSpeed={0.4}
          enableDamping
          dampingFactor={0.08}
          autoRotate={autoRotate}
          autoRotateSpeed={0.4}
        />
      )}
      {compact && <OrbitControls enableZoom={false} enablePan={false} enableRotate={false} autoRotate autoRotateSpeed={1.2} />}
    </Canvas>
  );
});
