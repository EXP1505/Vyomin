import { useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useTelemetryStore } from '../store/telemetryStore';

export const useTelemetrySocket = () => {
  const setFlights = useTelemetryStore((state) => state.setFlights);
  const clientRef = useRef(null);

  useEffect(() => {
    const wsUrl = import.meta.env.VITE_WS_TELEMETRY_URL || 'http://localhost:8080/ws-telemetry';
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      debug: (str) => {
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
