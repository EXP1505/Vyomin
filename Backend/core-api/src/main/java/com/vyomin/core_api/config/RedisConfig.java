package com.vyomin.core_api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // Exposed as its own bean (distinct type from Spring Boot 4's default tools.jackson.* mapper,
    // so this doesn't conflict with it) so callers can convert a cached value's raw
    // LinkedHashMap/List structure back into a typed DTO with ObjectMapper.convertValue(...) -
    // GenericJackson2JsonRedisSerializer only embeds "@class" type metadata when default typing is
    // explicitly enabled, which we deliberately don't do here (it re-enables Jackson's classic
    // polymorphic-deserialization gadget-chain risk for what would otherwise be plain, safe DTOs).
    @Bean
    public ObjectMapper redisObjectMapper() {
        // GenericJackson2JsonRedisSerializer's default ObjectMapper doesn't register
        // JavaTimeModule, so caching any DTO with a LocalDate/LocalDateTime field (e.g. the
        // country-dossier response) throws InvalidDefinitionException without this.
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                         ObjectMapper redisObjectMapper) {
        // Create a RedisTemplate and set the connection factory
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        // Set the key and value serializers to use String and JSON serialization
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));

        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));

        template.afterPropertiesSet();
        return template;
    }
}
