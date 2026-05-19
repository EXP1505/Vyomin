import React, { useEffect, useState, useRef } from 'react';
import ForceGraph2D from 'react-force-graph-2d';

export const GraphView = () => {
  const [graphData, setGraphData] = useState({ nodes: [], links: [] });
  const containerRef = useRef(null);
  const [dimensions, setDimensions] = useState({ width: 800, height: 600 });

  useEffect(() => {
    if (containerRef.current) {
      setDimensions({
        width: containerRef.current.offsetWidth,
        height: containerRef.current.offsetHeight
      });
    }
  }, []);

  useEffect(() => {
    const fetchGraphData = async () => {
      try {
        const response = await fetch('/api/intel/risk-path/defaultUser', {
          headers: {
            'Content-Type': 'application/json'
          }
        });
        
        if (response.ok) {
          const data = await response.json();
          setGraphData(data);
        }
      } catch (error) {
        console.error("Failed to fetch intelligence graph", error);
      }
    };

    fetchGraphData();
  }, []);

  return (
    <div className="flex flex-col h-full bg-slate-900 rounded-lg shadow-lg border border-slate-700 p-6 overflow-hidden">
      <div className="flex justify-between items-center mb-4 border-b border-slate-700 pb-2">
        <h2 className="text-2xl font-bold text-emerald-400">Intelligence Graph</h2>
        <div className="text-sm text-slate-400">Neo4j Force-Directed Canvas</div>
      </div>
      
      <div ref={containerRef} className="flex-grow rounded border border-slate-800 overflow-hidden relative z-0 bg-[#0f172a]">
        {graphData.nodes && graphData.nodes.length > 0 ? (
          <ForceGraph2D
            width={dimensions.width}
            height={dimensions.height}
            graphData={graphData}
            nodeLabel="name"
            nodeAutoColorBy="label"
            nodeCanvasObject={(node, ctx, globalScale) => {
              const label = node.name || node.id;
              const fontSize = 12 / globalScale;
              ctx.font = `${fontSize}px Sans-Serif`;
              ctx.fillStyle = node.color || '#10b981';
              
              ctx.beginPath();
              ctx.arc(node.x, node.y, 5, 0, 2 * Math.PI, false);
              ctx.fill();
              
              ctx.textAlign = 'center';
              ctx.textBaseline = 'middle';
              ctx.fillStyle = '#e2e8f0';
              ctx.fillText(label, node.x, node.y + 8);
            }}
          />
        ) : (
          <div className="flex h-full items-center justify-center text-slate-500">
            Awaiting Data Ingestion...
          </div>
        )}
      </div>
    </div>
  );
};
