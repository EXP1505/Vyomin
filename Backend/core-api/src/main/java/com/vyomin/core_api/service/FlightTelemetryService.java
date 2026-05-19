package com.vyomin.core_api.service;

import com.vyomin.core_api.model.telemetry.FlightTelemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class FlightTelemetryService {
    //redis template to store the flights data
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    // simp messaging template to broadcast the flights data
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    //redis key to store the flights data
    private final String REDIS_KEY = "FLIGHTS_LATEST";
    //random number generator to generate mock flight data
    private final Random random = new Random();
    //scheduled to fetch and broadcast the flights data every 3 seconds
    @Scheduled(fixedRate = 3000)
    public void fetchAndBroadcastFlights() {
        // Generate mock high-fidelity military flight data
        List<FlightTelemetry> flights = generateMockFlights();

        // Save latest snapshot to Redis
        redisTemplate.opsForValue().set(REDIS_KEY, flights);

        // Broadcast to WebSocket topic
        messagingTemplate.convertAndSend("/topic/flights", flights);
    }

    private List<FlightTelemetry> generateMockFlights() {
        List<FlightTelemetry> flights = new ArrayList<>();
        
        flights.add(new FlightTelemetry("GHOST01", 34.1 + random.nextDouble()*0.1, -118.2 + random.nextDouble()*0.1, 35000 + random.nextInt(1000), random.nextInt(360), System.currentTimeMillis()));
        flights.add(new FlightTelemetry("VIPER22", 36.1 + random.nextDouble()*0.1, -115.1 + random.nextDouble()*0.1, 28000 + random.nextInt(1000), random.nextInt(360), System.currentTimeMillis()));
        flights.add(new FlightTelemetry("REAPER9", 33.5 + random.nextDouble()*0.1, -112.0 + random.nextDouble()*0.1, 45000 + random.nextInt(1000), random.nextInt(360), System.currentTimeMillis()));

        return flights;
    }
}
