import { create } from 'zustand';

export const useAuthStore = create((set) => ({
  token: null,
  email: null,
  isAuthenticated: false,
  login: (token, email) => set({ token, email, isAuthenticated: true }),
  logout: () => set({ token: null, email: null, isAuthenticated: false }),
}));
