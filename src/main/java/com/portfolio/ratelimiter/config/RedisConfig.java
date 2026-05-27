package com.portfolio.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

    /**
     * Loads the Token Bucket Lua script from resources as a reusable Redis script.
     * The script returns a List containing two Longs:
     * - Element 0: Allowed status (1 = allowed, 0 = blocked)
     * - Element 1: Remaining tokens
     */
    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List> rateLimiterScript() {
        ClassPathResource resource = new ClassPathResource("scripts/rate_limiter.lua");
        return RedisScript.of(resource, (Class<List>) (Class<?>) List.class);
    }

    /**
     * Configures a reactive Redis template for key/value and hash operations using String keys and values.
     */
    @Bean
    @Primary
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(ReactiveRedisConnectionFactory factory) {
        StringRedisSerializer serializer = new StringRedisSerializer();
        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(serializer)
                .key(serializer)
                .value(serializer)
                .hashKey(serializer)
                .hashValue(serializer)
                .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }
}
