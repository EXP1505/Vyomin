package com.vyomin.core_api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
// This class configures WebSocket support for the application using Spring's STOMP protocol
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Configure the message broker to use a simple in-memory broker with a destination prefix of "/topic"
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    // Register a STOMP endpoint at "/ws-telemetry" that allows cross-origin requests and supports SockJS fallback options
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-telemetry")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
