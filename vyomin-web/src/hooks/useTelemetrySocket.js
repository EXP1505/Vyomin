import { useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useTelemetryStore } from '../store/telemetryStore';

const API_BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const useTelemetrySocket = () => {
  const setFlights = useTelemetryStore((state) => state.setFlights);
  const clientRef = useRef(null);

  useEffect(() => {
    // Broadcasts only go out to whoever is already subscribed when the 5-minute scheduled
    // job fires - a tab opened between ticks would otherwise sit empty until the next one.
    // The snapshot is already cached in Redis, so grab it once on mount (no OpenSky call,
    // doesn't touch the rate limit) while the socket connects for live updates.
    fetch(`${API_BASE}/api/telemetry/flights`)
      .then((res) => (res.ok ? res.json() : []))
      .then((flights) => {
        if (Array.isArray(flights) && flights.length > 0) {
          setFlights(flights);
        }
      })
      .catch(() => {
        // socket subscription below will populate the map once the next broadcast fires
      });

    const wsUrl = import.meta.env.VITE_WS_TELEMETRY_URL || 'http://localhost:8080/ws-telemetry';
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      debug: () => {
        // console.log(str);
      },
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
    });

    client.onConnect = () => {
      console.log('Connected to Telemetry Socket');
      client.subscribe('/topic/flights', (message) => {
        if (message.body) {
          const flightsData = JSON.parse(message.body);
          setFlights(flightsData);
        }
      });
    };

    client.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
      console.error('Additional details: ' + frame.body);
    };

    client.activate();
    clientRef.current = client;

    return () => {
      if (clientRef.current) {
        clientRef.current.deactivate();
      }
    };
  }, [setFlights]);
};
