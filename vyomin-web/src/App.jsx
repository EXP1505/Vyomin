import React, { useState } from 'react';
import { RadarView } from './components/RadarView';
import { GraphView } from './components/GraphView';

function App() {
  const [activeTab, setActiveTab] = useState('radar');

  return (
    <div className="flex h-screen bg-slate-900 text-slate-200">
      {/* Side Navigation */}
      <nav className="w-64 bg-slate-950 border-r border-slate-800 flex flex-col">
        <div className="p-6 border-b border-slate-800">
          <h1 className="text-2xl font-black text-emerald-500 tracking-tighter">VYOMIN</h1>
          <p className="text-xs text-slate-500 mt-1 uppercase tracking-widest">Command Center</p>
        </div>
        
        <div className="flex-grow p-4 space-y-2">
          <button 
            onClick={() => setActiveTab('radar')}
            className={`w-full text-left px-4 py-3 rounded-md transition-colors ${activeTab === 'radar' ? 'bg-emerald-900/30 text-emerald-400 border border-emerald-800/50' : 'hover:bg-slate-800 text-slate-400'}`}
          >
            Radar Telemetry
          </button>
          <button 
            onClick={() => setActiveTab('graph')}
            className={`w-full text-left px-4 py-3 rounded-md transition-colors ${activeTab === 'graph' ? 'bg-emerald-900/30 text-emerald-400 border border-emerald-800/50' : 'hover:bg-slate-800 text-slate-400'}`}
          >
            Intelligence Graph
          </button>
        </div>
        
        <div className="p-4 border-t border-slate-800 text-xs text-slate-600 text-center">
          SYSTEM ONLINE
        </div>
      </nav>

      {/* Main Viewing Area */}
      <main className="flex-grow p-6 overflow-hidden">
        {activeTab === 'radar' && <RadarView />}
        {activeTab === 'graph' && <GraphView />}
      </main>
    </div>
  );
}

export default App;
