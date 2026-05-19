import { create } from 'zustand';

export const useTelemetryStore = create((set) => ({
  flights: [],
  setFlights: (flights) => set({ flights }),
}));
