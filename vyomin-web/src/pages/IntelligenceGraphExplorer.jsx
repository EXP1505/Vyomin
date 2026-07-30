import { useEffect, useMemo, useRef, useState } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const API_BASE = 'http://localhost:8080/api/intel';
const WS_URL = import.meta.env.VITE_WS_TELEMETRY_URL || 'http://localhost:8080/ws-telemetry';
const MAX_AUTO_RETRIES_PER_QUERY = 1;

const SEARCH_TYPES = [
  { value: 'company', label: 'Company' },
  { value: 'sector', label: 'Sector' },
  { value: 'country', label: 'Country' },
  { value: 'conflict', label: 'Conflict' },
  { value: 'investor', label: 'Investor' },
  { value: 'supply', label: 'Supply' },
];

const NODE_COLORS = {
  company: '#3b82f6',
  conflict: '#ef4444',
  investor: '#22c55e',
  sector: '#a855f7',
  country: '#f97316',
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

export default function IntelligenceGraphExplorer() {
  const [query, setQuery] = useState('');
  const [type, setType] = useState('company');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [searchResponse, setSearchResponse] = useState(null);
  const [selectedNode, setSelectedNode] = useState(null);

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
    retryCountsRef.current.delete(`${type}:${query.trim()}`);
    executeSearch(query.trim(), type);
  };

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
    <div className="flex flex-col h-full bg-slate-900 text-slate-200 p-6 gap-4 overflow-hidden">
      <div>
        <h1 className="text-2xl font-bold text-emerald-400">Intelligence Graph Explorer</h1>
        <p className="text-sm text-slate-500">Search companies, sectors, countries, conflicts, investors and supply routes</p>
      </div>

      <form onSubmit={runSearch} className="flex gap-2 items-center">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search the intelligence graph..."
          className="flex-grow bg-slate-800 border border-slate-700 rounded-md px-4 py-2 text-sm placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-emerald-500"
        />
        <select
          value={type}
          onChange={(e) => setType(e.target.value)}
          className="bg-slate-800 border border-slate-700 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
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
          className="bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed text-white text-sm font-medium px-4 py-2 rounded-md transition-colors"
        >
          {loading ? 'Searching...' : 'Search'}
        </button>
      </form>

      {error && (
        <div className="bg-red-950/40 border border-red-800 text-red-300 text-sm px-4 py-2 rounded-md">
          {error}
        </div>
      )}

      {searchResponse?.loading && (
        <div className="flex items-center gap-2 bg-amber-950/40 border border-amber-800 text-amber-300 text-sm px-4 py-2 rounded-md">
          <span className="h-2 w-2 rounded-full bg-amber-400 animate-pulse" />
          No cached data for &quot;{searchResponse.query}&quot; — fetching live data in the background, this can take a few seconds...
        </div>
      )}

      <div className="flex flex-grow gap-4 overflow-hidden">
        <div className="w-72 flex-shrink-0 flex flex-col bg-slate-950 border border-slate-800 rounded-lg overflow-hidden">
          <div className="px-4 py-3 border-b border-slate-800 flex items-center justify-between">
            <span className="text-sm font-semibold text-slate-300">Results</span>
            <span className="text-xs text-slate-500">
              {searchResponse ? `${searchResponse.totalMatches} matches` : '—'}
            </span>
          </div>
          <div className="flex-grow overflow-y-auto divide-y divide-slate-800">
            {flatResults.length === 0 && (
              <div className="p-4 text-sm text-slate-500">No results yet</div>
            )}
            {flatResults.map((item) => (
              <button
                key={`${item.kind}-${item.id}`}
                onClick={() => setSelectedNode(nodeById.get(nodeId(item.kind, item.id)) ?? null)}
                className="w-full text-left px-4 py-3 hover:bg-slate-900 transition-colors"
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm text-slate-200 truncate">{item.name}</span>
                  <span
                    className="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded"
                    style={{ color: NODE_COLORS[item.kind], borderColor: NODE_COLORS[item.kind], borderWidth: 1 }}
                  >
                    {item.kind}
                  </span>
                </div>
                {item.score != null && (
                  <div className="text-xs text-slate-500 mt-1">Match score: {item.score}</div>
                )}
              </button>
            ))}
          </div>
        </div>

        <div
          ref={containerRef}
          className="flex-grow rounded-lg border border-slate-800 overflow-hidden relative z-0 bg-[#0f172a]"
        >
          {graphData.nodes.length > 0 ? (
            <ForceGraph2D
              width={dimensions.width}
              height={dimensions.height}
              graphData={graphData}
              nodeLabel="name"
              linkColor={() => 'rgba(148, 163, 184, 0.35)'}
              onNodeClick={(node) => setSelectedNode(node)}
              nodeCanvasObject={(node, ctx, globalScale) => {
                const label = node.name;
                const fontSize = 12 / globalScale;
                ctx.font = `${fontSize}px Sans-Serif`;

                ctx.beginPath();
                ctx.arc(node.x, node.y, selectedNode?.id === node.id ? 7 : 5, 0, 2 * Math.PI, false);
                ctx.fillStyle = node.color;
                ctx.fill();
                if (selectedNode?.id === node.id) {
                  ctx.strokeStyle = '#f8fafc';
                  ctx.lineWidth = 1.5 / globalScale;
                  ctx.stroke();
                }

                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillStyle = '#e2e8f0';
                ctx.fillText(label, node.x, node.y + 10);
              }}
            />
          ) : (
            <div className="flex h-full items-center justify-center text-slate-500 text-sm">
              Search the graph to see results
            </div>
          )}
        </div>

        <div className="w-80 flex-shrink-0 bg-slate-950 border border-slate-800 rounded-lg overflow-y-auto">
          <div className="px-4 py-3 border-b border-slate-800">
            <span className="text-sm font-semibold text-slate-300">Details</span>
          </div>
          {!selectedNode ? (
            <div className="p-4 text-sm text-slate-500">Select a node to see details</div>
          ) : (
            <div className="p-4 space-y-4">
              <div>
                <div
                  className="inline-block text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded mb-1"
                  style={{ color: NODE_COLORS[selectedNode.type], borderColor: NODE_COLORS[selectedNode.type], borderWidth: 1 }}
                >
                  {selectedNode.type}
                </div>
                <h3 className="text-lg font-semibold text-slate-100">{selectedNode.name}</h3>
              </div>

              <div>
                <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-2">Properties</h4>
                <dl className="text-sm space-y-1">
                  {Object.entries(selectedNode.raw ?? {})
                    .filter(([, value]) => typeof value !== 'object' || value === null)
                    .map(([key, value]) => (
                      <div key={key} className="flex justify-between gap-2">
                        <dt className="text-slate-500">{key}</dt>
                        <dd className="text-slate-300 text-right truncate">{String(value)}</dd>
                      </div>
                    ))}
                </dl>
              </div>

              <div>
                <h4 className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-2">
                  Connected entities ({connectedEntities.length})
                </h4>
                {connectedEntities.length === 0 ? (
                  <p className="text-sm text-slate-500">No connections in current results</p>
                ) : (
                  <ul className="space-y-2">
                    {connectedEntities.map(({ otherId, kind, score }) => {
                      const other = nodeById.get(otherId);
                      if (!other) return null;
                      return (
                        <li key={otherId}>
                          <button
                            onClick={() => setSelectedNode(other)}
                            className="w-full text-left px-3 py-2 rounded-md bg-slate-900 hover:bg-slate-800 transition-colors"
                          >
                            <div className="flex items-center justify-between gap-2">
                              <span className="text-sm text-slate-200 truncate">{other.name}</span>
                              <span
                                className="text-[10px] uppercase tracking-wide"
                                style={{ color: NODE_COLORS[other.type] }}
                              >
                                {other.type}
                              </span>
                            </div>
                            {kind === 'conflict' && score != null && (
                              <div className="text-xs text-slate-500 mt-0.5">Match score: {score}</div>
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
    </div>
  );
}
