import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export const useAuthStore = create(
  persist(
    (set) => ({
      token: null,
      email: null,
      name: null,
      pictureUrl: null,
      isAuthenticated: false,
      login: ({ token, email, name, pictureUrl }) =>
        set({ token, email, name: name ?? null, pictureUrl: pictureUrl ?? null, isAuthenticated: true }),
      logout: () => set({ token: null, email: null, name: null, pictureUrl: null, isAuthenticated: false }),
    }),
    { name: 'vyomin-auth' }
  )
);
