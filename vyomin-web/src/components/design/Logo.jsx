export function Logo({ size = 28 }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 40 40"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      style={{ animation: 'vyomin-blink 4s ease-in-out infinite', flexShrink: 0 }}
    >
      {/* outer cube */}
      <rect x="6" y="6" width="24" height="24" stroke="var(--accent)" strokeWidth="1.4" />
      {/* inner cube, offset to suggest the tesseract's second projection */}
      <rect x="14" y="14" width="24" height="24" stroke="var(--accent)" strokeWidth="1.4" opacity="0.6" />
      {/* connecting edges between corresponding corners */}
      <line x1="6" y1="6" x2="14" y2="14" stroke="var(--accent)" strokeWidth="1" opacity="0.6" />
      <line x1="30" y1="6" x2="38" y2="14" stroke="var(--accent)" strokeWidth="1" opacity="0.6" />
      <line x1="6" y1="30" x2="14" y2="38" stroke="var(--accent)" strokeWidth="1" opacity="0.6" />
      <line x1="30" y1="30" x2="38" y2="38" stroke="var(--accent)" strokeWidth="1" opacity="0.6" />
      {/* V at the center, straddling both cubes */}
      <path
        d="M13 15 L20 30 L27 15"
        stroke="var(--text)"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />

      <style>{`
        @keyframes vyomin-blink {
          0%, 88%, 100% { opacity: 1; }
          92% { opacity: 0.15; }
          96% { opacity: 1; }
        }
      `}</style>
    </svg>
  );
}
