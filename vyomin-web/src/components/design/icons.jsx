// Track-type icon set. `rotationDeg` rotates the glyph to match heading/bearing.
function IconBase({ size = 16, rotationDeg = 0, color = 'currentColor', children }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      style={{ transform: `rotate(${rotationDeg}deg)`, transition: 'transform 0.3s ease' }}
    >
      <g fill={color} stroke={color}>
        {children}
      </g>
    </svg>
  );
}

export function MilitaryIcon(props) {
  // Compact delta/fighter silhouette — shorter nose-to-tail than a full airliner outline.
  return (
    <IconBase {...props}>
      <path
        d="M12 4 L14 10 L19 13 L14 12.5 L13 18 L15 20 L12 19 L9 20 L11 18 L10 12.5 L5 13 L10 10 Z"
        strokeWidth="0"
      />
    </IconBase>
  );
}

export function CargoIcon(props) {
  return (
    <IconBase {...props}>
      <path
        d="M12 4 L13.2 10 L18.5 12.5 L13.2 13.3 L12.6 18 L14.2 20 L12 19.2 L9.8 20 L11.4 18 L10.8 13.3 L5.5 12.5 L10.8 10 Z"
        fill="none"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
    </IconBase>
  );
}

export function HelicopterIcon(props) {
  return (
    <IconBase {...props}>
      <line x1="2" y1="5" x2="22" y2="5" strokeWidth="1.8" />
      <line x1="12" y1="2" x2="12" y2="8" strokeWidth="1.8" />
      <rect x="9" y="8" width="6" height="9" rx="2" fill="none" strokeWidth="1.6" />
      <line x1="12" y1="17" x2="12" y2="21" strokeWidth="1.6" />
      <line x1="15" y1="20" x2="9" y2="20" strokeWidth="1.6" />
    </IconBase>
  );
}

export function DroneIcon(props) {
  return (
    <IconBase {...props}>
      <circle cx="6" cy="6" r="3" fill="none" strokeWidth="1.6" />
      <circle cx="18" cy="6" r="3" fill="none" strokeWidth="1.6" />
      <circle cx="6" cy="18" r="3" fill="none" strokeWidth="1.6" />
      <circle cx="18" cy="18" r="3" fill="none" strokeWidth="1.6" />
      <line x1="8.2" y1="8.2" x2="10.8" y2="10.8" strokeWidth="1.6" />
      <line x1="15.8" y1="8.2" x2="13.2" y2="10.8" strokeWidth="1.6" />
      <line x1="8.2" y1="15.8" x2="10.8" y2="13.2" strokeWidth="1.6" />
      <line x1="15.8" y1="15.8" x2="13.2" y2="13.2" strokeWidth="1.6" />
      <rect x="10.5" y="10.5" width="3" height="3" fill="currentColor" strokeWidth="0" />
    </IconBase>
  );
}

export function UnknownIcon(props) {
  return (
    <IconBase {...props}>
      <path
        d="M12 2 L22 12 L12 22 L2 12 Z"
        fill="none"
        strokeWidth="1.6"
        strokeDasharray="3 3"
      />
    </IconBase>
  );
}

