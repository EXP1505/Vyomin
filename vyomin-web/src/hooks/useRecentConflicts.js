import { useEffect, useState } from 'react';

const API_BASE = 'http://localhost:8080/api/intel';
const POLL_MS = 20000;

// Bounded /conflicts/recent read (see ConflictRepository.findTop300ByOrderByDateReportedDesc) -
// used for both the hero globe's flashpoint markers and the live signal feed.
export function useRecentConflicts() {
  const [conflicts, setConflicts] = useState([]);

  useEffect(() => {
    let cancelled = false;
    const load = () => {
      fetch(`${API_BASE}/conflicts/recent`)
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
