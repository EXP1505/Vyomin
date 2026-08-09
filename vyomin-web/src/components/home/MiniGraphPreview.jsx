import { useMemo } from 'react';

const COLORS = { conflict: '#ffb020', country: '#5b8dee' };

// Built directly from the recent-conflicts feed rather than the Intelligence Graph page's own
// store, so this preview has real data to animate even before a visitor ever opens that page.
//
// Laid out deterministically (conflicts in an inner ring, countries in an outer ring) rather
// than with a live force simulation - react-force-graph-2d's physics has no counterforce to
// its default center-force for a graph this sparse (most conflicts link to just 1-2 countries,
// so it's mostly disconnected clusters), and with the sim left running it collapses every
// cluster onto the same point within a few seconds. A static layout can't collapse.
function buildMiniGraph(conflicts, width, height) {
  const cx = width / 2;
  const cy = height / 2;
  const conflictRadius = Math.min(width, height) * 0.2;
  const countryRadius = Math.min(width, height) * 0.42;

  const countryNodes = new Map();
  const conflictNodes = [];
  const links = [];

  conflicts.slice(0, 25).forEach((c) => {
    const cid = `c-${c.id}`;
    conflictNodes.push({ id: cid, color: COLORS.conflict, r: 2.5 });
    (c.involvedCountries || []).forEach((co) => {
      const coId = `co-${co.id}`;
      if (!countryNodes.has(coId)) countryNodes.set(coId, { id: coId, color: COLORS.country, r: 3.5 });
      links.push({ source: cid, target: coId });
    });
  });

  const positioned = new Map();
  conflictNodes.forEach((n, i) => {
    const angle = (i / Math.max(1, conflictNodes.length)) * Math.PI * 2;
    positioned.set(n.id, { ...n, x: cx + Math.cos(angle) * conflictRadius, y: cy + Math.sin(angle) * conflictRadius });
  });
  [...countryNodes.values()].forEach((n, i, arr) => {
    const angle = (i / Math.max(1, arr.length)) * Math.PI * 2;
    positioned.set(n.id, { ...n, x: cx + Math.cos(angle) * countryRadius, y: cy + Math.sin(angle) * countryRadius });
  });

  return { nodes: [...positioned.values()], links, positioned };
}

export function MiniGraphPreview({ conflicts = [], width = 240, height = 150 }) {
  const { nodes, links, positioned } = useMemo(() => buildMiniGraph(conflicts, width, height), [conflicts, width, height]);

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
      {links.map((l, i) => {
        const a = positioned.get(l.source);
        const b = positioned.get(l.target);
        if (!a || !b) return null;
        return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="rgba(124,134,152,0.35)" strokeWidth={0.6} />;
      })}
      {nodes.map((n) => (
        <circle key={n.id} cx={n.x} cy={n.y} r={n.r} fill={n.color} />
      ))}
    </svg>
  );
}