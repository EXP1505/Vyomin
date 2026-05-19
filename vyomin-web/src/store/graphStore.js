import { create } from 'zustand';

export const useGraphStore = create((set) => ({
  nodes: [],
  edges: [],
  setGraphData: (nodes, edges) => set({ nodes, edges }),
  clearGraph: () => set({ nodes: [], edges: [] }),
}));
