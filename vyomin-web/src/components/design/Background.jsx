// Layered tactical-HUD backdrop: lat/long grid + vignette + grain + scanlines.
// Mounted once, fixed behind all page content.
export function Background() {
  return (
    <div className="pointer-events-none fixed inset-0 z-0 overflow-hidden" style={{ background: 'var(--bg-base)' }}>
      <svg className="absolute inset-0 h-full w-full" style={{ opacity: 0.04 }}>
        <defs>
          <pattern id="latlong-grid" width="48" height="48" patternUnits="userSpaceOnUse">
            <path d="M 48 0 L 0 0 0 48" fill="none" stroke="var(--text)" strokeWidth="1" />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#latlong-grid)" />
      </svg>

      <div
        className="absolute inset-0"
        style={{
          background: 'radial-gradient(circle at 85% 10%, rgba(255,176,32,0.06), transparent 55%)',
        }}
      />

      <svg className="absolute inset-0 h-full w-full" style={{ opacity: 0.025, mixBlendMode: 'overlay' }}>
        <filter id="grain-turbulence">
          <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" />
          <feColorMatrix type="saturate" values="0" />
        </filter>
        <rect width="100%" height="100%" filter="url(#grain-turbulence)" />
      </svg>

      <div
        className="absolute inset-0"
        style={{
          background: 'repeating-linear-gradient(to bottom, rgba(255,255,255,0.35) 0px, rgba(255,255,255,0.35) 1px, transparent 1px, transparent 3px)',
          opacity: 0.015,
        }}
      />
    </div>
  );
}
