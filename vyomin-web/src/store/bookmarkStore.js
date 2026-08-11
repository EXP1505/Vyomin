import { create } from 'zustand';
import { apiFetch } from '../lib/api';

function entityKey(entityType, entityId) {
  return `${entityType}-${entityId}`;
}

export const useBookmarkStore = create((set, get) => ({
  stocks: new Set(),
  entities: new Map(),
  loaded: false,

  fetchAll: async () => {
    try {
      const [stocks, entities] = await Promise.all([
        apiFetch('/api/bookmarks/stocks'),
        apiFetch('/api/bookmarks/entities'),
      ]);
      set({
        stocks: new Set((stocks || []).map((s) => s.symbol)),
        entities: new Map((entities || []).map((e) => [entityKey(e.entityType, e.entityId), e])),
        loaded: true,
      });
    } catch {
      // Not authenticated or request failed - leave bookmarks empty.
    }
  },

  clear: () => set({ stocks: new Set(), entities: new Map(), loaded: false }),

  isStockBookmarked: (symbol) => get().stocks.has(symbol),
  isEntityBookmarked: (entityType, entityId) => get().entities.has(entityKey(entityType, entityId)),

  toggleStock: async (symbol) => {
    const sym = symbol.toUpperCase();
    const bookmarked = get().stocks.has(sym);
    set((state) => {
      const next = new Set(state.stocks);
      bookmarked ? next.delete(sym) : next.add(sym);
      return { stocks: next };
    });
    try {
      if (bookmarked) {
        await apiFetch(`/api/bookmarks/stocks/${encodeURIComponent(sym)}`, { method: 'DELETE' });
      } else {
        await apiFetch('/api/bookmarks/stocks', { method: 'POST', body: JSON.stringify({ symbol: sym }) });
      }
    } catch {
      // Roll back on failure.
      set((state) => {
        const next = new Set(state.stocks);
        bookmarked ? next.add(sym) : next.delete(sym);
        return { stocks: next };
      });
    }
  },

  toggleEntity: async (entityType, entityId, entityName) => {
    const key = entityKey(entityType, entityId);
    const bookmarked = get().entities.has(key);
    set((state) => {
      const next = new Map(state.entities);
      bookmarked ? next.delete(key) : next.set(key, { entityType, entityId, entityName });
      return { entities: next };
    });
    try {
      if (bookmarked) {
        await apiFetch(`/api/bookmarks/entities/${entityType}/${encodeURIComponent(entityId)}`, { method: 'DELETE' });
      } else {
        await apiFetch('/api/bookmarks/entities', {
          method: 'POST',
          body: JSON.stringify({ entityType, entityId: String(entityId), entityName }),
        });
      }
    } catch {
      set((state) => {
        const next = new Map(state.entities);
        bookmarked ? next.set(key, { entityType, entityId, entityName }) : next.delete(key);
        return { entities: next };
      });
    }
  },
}));

// Set/Map identity changes on every store update, which would re-trigger effects that key off
// the bookmark list even when its contents didn't change. This returns a value-stable string
// (equal strings are === in JS) so consuming effects only re-run when the symbols actually change.
export function useBookmarkedStockSymbols() {
  return useBookmarkStore((s) => Array.from(s.stocks).sort().join(','));
}
