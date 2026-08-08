import { useEffect, useMemo, useRef, useState } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const API_BASE = 'http://localhost:8080/api/intel';
const WS_URL = import.meta.env.VITE_WS_TELEMETRY_URL || 'http://localhost:8080/ws-telemetry';
const MAX_AUTO_RETRIES_PER_QUERY = 1;

// Company/Sector/Investor/Supply are hidden for now: GDELT (the only ingestion source wired
// up today) only ever produces Conflict + Country nodes. Company/Sector have a thin Finnhub
// path; Investor/Supply have no ingestion path at all. Re-add once there's a real data source.
const SEARCH_TYPES = [
  { value: 'conflict', label: 'Conflict' },
  { value: 'country', label: 'Country' },
];

// Reduced to 3 hues + gray per the design system, rather than a 5-color rainbow.
const NODE_COLORS = {
  conflict: '#ffb020',
  company: '#35d6b8',
  investor: '#35d6b8',
  country: '#5b8dee',
  sector: '#7c8698',
};

function nodeId(kind, id) {
  return `${kind}-${id}`;
}

function buildGraphData(results) {
  const nodes = new Map();
  const links = [];

  const companies = results?.companies ?? [];
  const conflicts = results?.conflicts ?? [];
  const investors = results?.investors ?? [];
  const sectors = results?.sectors ?? [];
  const countries = results?.countries ?? [];
  const matchScores = results?.matchScores ?? {};

  companies.forEach((c) => {
    if (c?.id == null) return;
    nodes.set(nodeId('company', c.id), {
      id: nodeId('company', c.id),
      name: c.name ?? c.ticker ?? `Company ${c.id}`,
      type: 'company',
      color: NODE_COLORS.company,
      raw: c,
    });

    if (c.sector?.id != null) {
      const sId = nodeId('sector', c.sector.id);
      if (!nodes.has(sId)) {
        nodes.set(sId, {
          id: sId,
          name: c.sector.name ?? `Sector ${c.sector.id}`,
          type: 'sector',
          color: NODE_COLORS.sector,
          raw: c.sector,
        });
      }
      links.push({ source: nodeId('company', c.id), target: sId, kind: 'sector' });
    }

    (c.investors ?? []).forEach((inv) => {
      if (inv?.id == null) return;
      const iId = nodeId('investor', inv.id);
      if (!nodes.has(iId)) {
        nodes.set(iId, {
          id: iId,
          name: inv.name ?? `Investor ${inv.id}`,
          type: 'investor',
          color: NODE_COLORS.investor,
          raw: inv,
        });
      }
      links.push({ source: iId, target: nodeId('company', c.id), kind: 'investor' });
    });
  });

  investors.forEach((inv) => {
    if (inv?.id == null) return;
    const iId = nodeId('investor', inv.id);
    if (!nodes.has(iId)) {
      nodes.set(iId, {
        id: iId,
        name: inv.name ?? `Investor ${inv.id}`,
        type: 'investor',
        color: NODE_COLORS.investor,
        raw: inv,
      });
    }
  });

  sectors.forEach((s) => {
    if (s?.id == null) return;
    const sId = nodeId('sector', s.id);
    if (!nodes.has(sId)) {
      nodes.set(sId, {
        id: sId,
        name: s.name ?? `Sector ${s.id}`,
        type: 'sector',
        color: NODE_COLORS.sector,
        raw: s,
      });
    }
  });

  countries.forEach((co) => {
    if (co?.id == null) return;
    const coId = nodeId('country', co.id);
    if (!nodes.has(coId)) {
      nodes.set(coId, {
        id: coId,
        name: co.name ?? `Country ${co.id}`,
        type: 'country',
        color: NODE_COLORS.country,
        raw: co,
      });
    }
  });

  conflicts.forEach((cf) => {
    if (cf?.id == null) return;
    const cfId = nodeId('conflict', cf.id);
    nodes.set(cfId, {
      id: cfId,
      name: cf.name ?? `Conflict ${cf.id}`,
      type: 'conflict',
      color: NODE_COLORS.conflict,
      raw: cf,
    });

    companies.forEach((c) => {
      if (c?.id == null) return;
      const score = matchScores[c.name];
      if (score != null) {
        links.push({
          source: nodeId('company', c.id),
          target: cfId,
          kind: 'conflict',
          score,
        });
      }
    });

    (cf.involvedCountries ?? []).forEach((co) => {
      if (co?.id == null) return;
      const coId = nodeId('country', co.id);
      if (!nodes.has(coId)) {
        nodes.set(coId, {
          id: coId,
          name: co.name ?? `Country ${co.id}`,
          type: 'country',
          color: NODE_COLORS.country,
          raw: co,
        });
      }
      links.push({ source: cfId, target: coId, kind: 'country' });
    });
  });

  return { nodes: Array.from(nodes.values()), links };
}

export default function IntelligenceGraphExplorer({ initialSearch }) {
  const [query, setQuery] = useState(initialSearch?.query ?? '');
  const [type, setType] = useState(initialSearch?.type ?? 'conflict');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [searchResponse, setSearchResponse] = useState(null);
  const [selectedNode, setSelectedNode] = useState(null);
  const [hoverNode, setHoverNode] = useState(null);
  const [hasSearched, setHasSearched] = useState(Boolean(initialSearch?.query));

  const containerRef = useRef(null);
  const [dimensions, setDimensions] = useState({ width: 800, height: 600 });

  useEffect(() => {
    if (!containerRef.current) return;
    const observer = new ResizeObserver(([entry]) => {
      setDimensions({
        width: entry.contentRect.width,
        height: entry.contentRect.height,
      });
    });
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, []);

  const graphData = useMemo(() => buildGraphData(searchResponse?.results), [searchResponse]);

  const flatResults = useMemo(() => {
    const results = searchResponse?.results;
    if (!results) return [];
    const matchScores = results.matchScores ?? {};
    return [
      ...(results.companies ?? []).map((c) => ({
        kind: 'company',
        id: c.id,
        name: c.name,
        score: matchScores[c.name] ?? null,
        raw: c,
      })),
      ...(results.conflicts ?? []).map((c) => ({ kind: 'conflict', id: c.id, name: c.name, score: null, raw: c })),
      ...(results.investors ?? []).map((i) => ({ kind: 'investor', id: i.id, name: i.name, score: null, raw: i })),
      ...(results.sectors ?? []).map((s) => ({ kind: 'sector', id: s.id, name: s.name, score: null, raw: s })),
      ...(results.countries ?? []).map((co) => ({ kind: 'country', id: co.id, name: co.name, score: null, raw: co })),
    ];
  }, [searchResponse]);

  const retryCountsRef = useRef(new Map());

  const executeSearch = async (searchQuery, searchType) => {
    setLoading(true);
    setError(null);

    try {
      const url = `${API_BASE}/search?q=${encodeURIComponent(searchQuery)}&type=${encodeURIComponent(searchType)}`;
      const response = await fetch(url);
      const data = await response.json();

      if (!response.ok) {
        setError(data?.error ?? 'Search failed');
        setSearchResponse(null);
        return;
      }

      setSearchResponse(data);
    } catch {
      setError('Failed to reach intelligence API');
      setSearchResponse(null);
    } finally {
      setLoading(false);
    }
  };

  const runSearch = (e) => {
    e?.preventDefault();
    if (!query.trim()) return;
    setSelectedNode(null);
    setHasSearched(true);
    retryCountsRef.current.delete(`${type}:${query.trim()}`);
    executeSearch(query.trim(), type);
  };

  // Seeded from the Financial Telemetry page's "related conflict events" cross-link.
  useEffect(() => {
    if (!initialSearch?.query) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setQuery(initialSearch.query);
    setType(initialSearch.type ?? 'conflict');
    setSelectedNode(null);
    setHasSearched(true);
    executeSearch(initialSearch.query, initialSearch.type ?? 'conflict');
  }, [initialSearch]);

  // When a search returns nothing cached, the backend queues a background fetch and hands
  // back a requestId. Subscribe to that request's topic and re-run the search once it
  // finishes, so the graph updates itself without the user re-submitting the form.
  useEffect(() => {
    if (!searchResponse?.loading || !searchResponse?.requestId) return;

    const searchQuery = searchResponse.query;
    const searchType = searchResponse.type;
    const key = `${searchType}:${searchQuery}`;

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      debug: () => {},
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    client.onConnect = () => {
      client.subscribe(`/topic/data-fetch/${searchResponse.requestId}`, (message) => {
        if (!message.body) return;
        const payload = JSON.parse(message.body);

        if (!payload.success) {
          setError(payload.error ?? 'Background data fetch failed');
          return;
        }

        const retries = retryCountsRef.current.get(key) ?? 0;
        if (retries >= MAX_AUTO_RETRIES_PER_QUERY) return;
        retryCountsRef.current.set(key, retries + 1);
        executeSearch(searchQuery, searchType);
      });
    };

    client.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
    };

    client.activate();
    return () => client.deactivate();
  }, [searchResponse?.requestId, searchResponse?.loading, searchResponse?.query, searchResponse?.type]);

  const connectedEntities = useMemo(() => {
    if (!selectedNode) return [];
    return graphData.links
      .filter((l) => {
        const sourceId = typeof l.source === 'object' ? l.source.id : l.source;
        const targetId = typeof l.target === 'object' ? l.target.id : l.target;
        return sourceId === selectedNode.id || targetId === selectedNode.id;
      })
      .map((l) => {
        const sourceId = typeof l.source === 'object' ? l.source.id : l.source;
        const targetId = typeof l.target === 'object' ? l.target.id : l.target;
        const otherId = sourceId === selectedNode.id ? targetId : sourceId;
        return { otherId, kind: l.kind, score: l.score };
      });
  }, [selectedNode, graphData.links]);

  const nodeById = useMemo(() => {
    const map = new Map();
    graphData.nodes.forEach((n) => map.set(n.id, n));
    return map;
  }, [graphData.nodes]);

  return (
    <div className="relative h-full w-full overflow-hidden" style={{ color: 'var(--text)' }}>
      {/* Full-bleed graph canvas sits behind everything */}
      <div
        ref={containerRef}
        className="absolute inset-0 z-0"
        style={{ background: 'var(--panel-2)' }}
      >
        {graphData.nodes.length > 0 ? (
          <ForceGraph2D
            width={dimensions.width}
            height={dimensions.height}
            graphData={graphData}
            nodeLabel="name"
            linkColor={() => 'rgba(124, 134, 152, 0.3)'}
            linkCurvature={0.25}
            onNodeClick={(node) => setSelectedNode(node)}
            onNodeHover={(node) => setHoverNode(node)}
            nodeCanvasObject={(node, ctx, globalScale) => {
              const isSelected = selectedNode?.id === node.id;
              const isHovered = hoverNode?.id === node.id;

              ctx.beginPath();
              ctx.arc(node.x, node.y, isSelected ? 7 : 5, 0, 2 * Math.PI, false);
              ctx.fillStyle = node.color;
              ctx.fill();
              if (isSelected) {
                ctx.strokeStyle = '#ffb020';
                ctx.lineWidth = 1.5 / globalScale;
                ctx.stroke();
              }

              // Labels only render on hover/selection to avoid collision clutter at high node counts.
              if (isSelected || isHovered) {
                const fontSize = 12 / globalScale;
                ctx.font = `${fontSize}px 'JetBrains Mono', monospace`;
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillStyle = '#e6e9ef';
                ctx.fillText(node.name, node.x, node.y + 10);
              }
            }}
            nodePointerAreaPaint={(node, color, ctx) => {
              ctx.fillStyle = color;
              ctx.beginPath();
              ctx.arc(node.x, node.y, selectedNode?.id === node.id ? 7 : 5, 0, 2 * Math.PI, false);
              ctx.fill();
            }}
          />
        ) : (
          !hasSearched && (
            <div className="flex h-full items-center justify-center text-sm" style={{ color: 'var(--text-faint)' }}>
              &nbsp;
            </div>
          )
        )}
      </div>

      {/* Search bar: centered overlay on first load, docks to the top once a search runs */}
      <div
        className="absolute left-1/2 z-20 w-full max-w-2xl px-6 transition-all duration-500 ease-out"
        style={
          hasSearched
            ? { top: '1.25rem', transform: 'translateX(-50%)' }
            : { top: '50%', transform: 'translate(-50%, -50%)' }
        }
      >
        {!hasSearched && (
          <div className="mb-5 text-center">
            <h1 className="text-2xl font-semibold" style={{ color: 'var(--text)' }}>Intelligence Graph Explorer</h1>
            <p className="mt-1 text-sm" style={{ color: 'var(--text-dim)' }}>
              Search a country or conflict — companies, sectors, and investors surface as connected entities
            </p>
          </div>
        )}

        <form onSubmit={runSearch} className="flex items-center gap-2 shadow-2xl">
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search the country or conflict…"
            autoFocus
            className="font-mono-data flex-grow border px-4 py-3 text-sm focus:outline-none transition-colors"
            style={{ background: 'var(--panel)', borderColor: 'var(--hairline)', color: 'var(--text)' }}
            onFocus={(e) => (e.target.style.borderColor = 'var(--accent)')}
            onBlur={(e) => (e.target.style.borderColor = 'var(--hairline)')}
          />
          <select
            value={type}
            onChange={(e) => setType(e.target.value)}
            className="font-mono-data border px-3 py-3 text-sm focus:outline-none"
            style={{ background: 'var(--panel)', borderColor: 'var(--hairline)', color: 'var(--text)' }}
          >
            {SEARCH_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
          <button
            type="submit"
            disabled={loading}
            className="text-sm font-medium px-5 py-3 border transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            style={{ borderColor: 'var(--accent)', color: loading ? 'var(--text-dim)' : 'var(--accent)', background: 'var(--accent-soft)' }}
          >
            {loading ? 'Searching...' : 'Search'}
          </button>
        </form>

        {error && (
          <div className="mt-2 text-sm px-4 py-2 border" style={{ background: 'rgba(255,92,108,0.1)', borderColor: 'var(--negative)', color: 'var(--negative)' }}>
            {error}
          </div>
        )}

        {searchResponse?.loading && (
          <div className="mt-2 flex items-center gap-2 text-sm px-4 py-2 border" style={{ background: 'var(--accent-soft)', borderColor: 'var(--accent)', color: 'var(--accent)' }}>
            <span className="h-2 w-2 rounded-full" style={{ background: 'var(--accent)', animation: 'pulse 2s infinite' }} />
            No cached data for &quot;{searchResponse.query}&quot; — fetching live data in the background, this can take a few seconds...
          </div>
        )}
      </div>

      {/* Results: slides in from the left once a search has run */}
      <div
        className="absolute left-0 top-0 z-10 flex h-full w-72 flex-col overflow-hidden border-r transition-transform duration-500 ease-out"
        style={{
          borderColor: 'var(--hairline)',
          background: 'var(--panel)',
          transform: hasSearched ? 'translateX(0)' : 'translateX(-100%)',
        }}
      >
        <div className="px-4 py-3 border-b flex items-center justify-between" style={{ borderColor: 'var(--hairline)' }}>
          <span className="text-sm font-semibold" style={{ color: 'var(--text)' }}>Results</span>
          <span className="font-mono-data text-xs" style={{ color: 'var(--text-faint)' }}>
            {searchResponse ? `${searchResponse.totalMatches} matches` : '—'}
          </span>
        </div>
        <div className="flex-grow overflow-y-auto divide-y" style={{ borderColor: 'var(--hairline)' }}>
          {flatResults.length === 0 && (
            <div className="p-4 text-sm" style={{ color: 'var(--text-faint)' }}>No results yet</div>
          )}
          {flatResults.map((item) => (
            <button
              key={`${item.kind}-${item.id}`}
              onClick={() => setSelectedNode(nodeById.get(nodeId(item.kind, item.id)) ?? null)}
              className="w-full text-left px-4 py-3 transition-colors border-b"
              style={{ borderColor: 'var(--hairline)' }}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm truncate" style={{ color: 'var(--text)' }}>{item.name}</span>
                <span
                  className="font-mono-data text-[10px] uppercase tracking-wide px-1.5 py-0.5"
                  style={{ color: NODE_COLORS[item.kind], borderColor: NODE_COLORS[item.kind], borderWidth: 1 }}
                >
                  {item.kind}
                </span>
              </div>
              {item.score != null && (
                <div className="font-mono-data text-xs mt-1" style={{ color: 'var(--text-faint)' }}>Match score: {item.score}</div>
              )}
            </button>
          ))}
        </div>
      </div>

      {/* Details: slides in from the right once a search has run */}
      <div
        className="absolute right-0 top-0 z-10 h-full w-80 overflow-y-auto border-l transition-transform duration-500 ease-out"
        style={{
          borderColor: 'var(--hairline)',
          background: 'var(--panel)',
          transform: hasSearched ? 'translateX(0)' : 'translateX(100%)',
        }}
      >
        <div className="px-4 py-3 border-b" style={{ borderColor: 'var(--hairline)' }}>
          <span className="text-sm font-semibold" style={{ color: 'var(--text)' }}>Details</span>
        </div>
        {!selectedNode ? (
          <div className="p-4 text-sm" style={{ color: 'var(--text-faint)' }}>Select a node to see details</div>
        ) : (
          <div className="p-4 space-y-4">
            <div>
              <div
                className="font-mono-data inline-block text-[10px] uppercase tracking-wide px-1.5 py-0.5 mb-1"
                style={{ color: NODE_COLORS[selectedNode.type], borderColor: NODE_COLORS[selectedNode.type], borderWidth: 1 }}
              >
                {selectedNode.type}
              </div>
              <h3 className="text-lg font-semibold" style={{ color: 'var(--text)' }}>{selectedNode.name}</h3>
            </div>

            <div>
              <h4 className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: 'var(--text-dim)' }}>Properties</h4>
              <dl className="font-mono-data text-sm space-y-1">
                {Object.entries(selectedNode.raw ?? {})
                  .filter(([, value]) => typeof value !== 'object' || value === null)
                  .map(([key, value]) => (
                    <div key={key} className="flex justify-between gap-2">
                      <dt style={{ color: 'var(--text-faint)' }}>{key}</dt>
                      <dd className="text-right truncate" style={{ color: 'var(--text)' }}>{String(value)}</dd>
                    </div>
                  ))}
              </dl>
            </div>

            <div>
              <h4 className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: 'var(--text-dim)' }}>
                Connected entities ({connectedEntities.length})
              </h4>
              {connectedEntities.length === 0 ? (
                <p className="text-sm" style={{ color: 'var(--text-faint)' }}>No connections in current results</p>
              ) : (
                <ul className="space-y-2">
                  {connectedEntities.map(({ otherId, kind, score }) => {
                    const other = nodeById.get(otherId);
                    if (!other) return null;
                    return (
                      <li key={otherId}>
                        <button
                          onClick={() => setSelectedNode(other)}
                          className="w-full text-left px-3 py-2 transition-colors border"
                          style={{ borderColor: 'var(--hairline)', background: 'var(--panel-2)' }}
                        >
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-sm truncate" style={{ color: 'var(--text)' }}>{other.name}</span>
                            <span
                              className="font-mono-data text-[10px] uppercase tracking-wide"
                              style={{ color: NODE_COLORS[other.type] }}
                            >
                              {other.type}
                            </span>
                          </div>
                          {kind === 'conflict' && score != null && (
                            <div className="font-mono-data text-xs mt-0.5" style={{ color: 'var(--text-faint)' }}>Match score: {score}</div>
                          )}
                        </button>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
