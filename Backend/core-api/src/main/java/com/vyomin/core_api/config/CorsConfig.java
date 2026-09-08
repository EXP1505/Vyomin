package com.vyomin.core_api.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    // Local dev origins are always allowed; APP_CORS_EXTRA_ORIGINS adds the deployed frontend
    // URL(s) (comma-separated, e.g. the Render static site URL) without touching this list.
    @Value("${app.cors.extra-origins:}")
    private String extraOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        List<String> origins = new ArrayList<>(List.of(
                "http://localhost:5173",
                "http://localhost:3000",
                "http://localhost:4173",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:3000",
                "http://127.0.0.1:4173"));
        if (extraOrigins != null && !extraOrigins.isBlank()) {
            for (String origin : extraOrigins.split(",")) {
                if (!origin.isBlank()) {
                    origins.add(origin.trim());
                }
            }
        }

        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins.toArray(new String[0]))
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }
        };
    }
}
