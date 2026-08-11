import { useAuthStore } from '../store/authStore';

export async function apiFetch(path, options = {}) {
  const token = useAuthStore.getState().token;
  const headers = {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(options.headers || {}),
  };
  const res = await fetch(path, { ...options, headers });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body?.error || (typeof body === 'string' ? body : `Request failed (${res.status})`));
  }
  if (res.status === 204) return null;
  return res.json().catch(() => null);
}
