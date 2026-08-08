import { Panel } from '../design/Panel';

function Row({ label, value }) {
  return (
    <div className="flex items-center justify-between py-1.5 border-b" style={{ borderColor: 'var(--hairline)' }}>
      <span className="text-xs" style={{ color: 'var(--text-dim)' }}>{label}</span>
      <span className="font-mono-data text-xs" style={{ color: 'var(--text)' }}>{value}</span>
    </div>
  );
}

export function TrackDetailPanel({ flight, onClose }) {
  return (
    <div
      className="absolute right-0 top-0 h-full w-80 transition-transform duration-300"
      style={{ transform: flight ? 'translateX(0)' : 'translateX(100%)' }}
    >
      {flight && (
        <Panel className="h-full p-5">
          <div className="flex items-start justify-between">
            <div>
              <div className="text-sm font-semibold tracking-wide" style={{ color: 'var(--accent)' }}>
                {flight.callsign}
              </div>
              <div className="font-mono-data text-[10px] mt-0.5" style={{ color: 'var(--text-faint)' }}>
                {flight.flightType}
              </div>
            </div>
            <button onClick={onClose} className="text-sm" style={{ color: 'var(--text-dim)' }}>
              &times;
            </button>
          </div>

          <div className="mt-4">
            <Row label="LATITUDE" value={flight.latitude?.toFixed(4)} />
            <Row label="LONGITUDE" value={flight.longitude?.toFixed(4)} />
            <Row label="HEADING" value={`${flight.heading?.toFixed(0) ?? '--'}°`} />
            <Row label="ALTITUDE" value={`${flight.altitude?.toLocaleString() ?? '--'} m`} />
            <Row label="MODEL" value={flight.aircraftModel || '--'} />
            <Row label="REGISTRATION" value={flight.registration || '--'} />
            <Row label="CLASSIFICATION" value={flight.flightType} />
          </div>
        </Panel>
      )}
    </div>
  );
}
