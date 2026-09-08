import { useAuthStore } from '../store/authStore';

// Empty by default so relative paths ("/api/...") keep working as-is when the frontend and
// backend share an origin (local Nginx proxy in docker-compose, or Vite's dev proxy). Set
// VITE_API_BASE_URL when the frontend is deployed separately from the backend (e.g. Render
// static site + Render web service on different hosts) to point requests at the right origin.
export const API_BASE = import.meta.env.VITE_API_BASE_URL || '';

export async function apiFetch(path, options = {}) {
  const token = useAuthStore.getState().token;
  const headers = {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  };
  const res = await fetch(`${API_BASE}${path}`, { ...options, headers });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body?.error || (typeof body === 'string' ? body : `Request failed (${res.status})`));
  }
  if (res.status === 204) return null;
  return res.json().catch(() => null);
}
