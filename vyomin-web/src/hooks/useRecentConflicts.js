import { useEffect, useState } from 'react';
import { API_BASE } from '../lib/api';

const POLL_MS = 20000;

// Bounded /conflicts/recent read (see ConflictRepository.findTop300ByOrderByDateReportedDesc) -
// used for both the hero globe's flashpoint markers and the live signal feed.
export function useRecentConflicts() {
  const [conflicts, setConflicts] = useState([]);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      fetch(`${API_BASE}/api/intel/conflicts/recent`)
        .then((r) => (r.ok ? r.json() : []))
        .then((data) => {
          if (!cancelled && Array.isArray(data)) setConflicts(data);
        })
        .catch(() => {});
    };
    load();
    const id = setInterval(load, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  return conflicts;
}
