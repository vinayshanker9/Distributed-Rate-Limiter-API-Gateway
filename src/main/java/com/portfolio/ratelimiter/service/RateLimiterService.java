package com.portfolio.ratelimiter.service;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

@Service
public class RateLimiterService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RateLimiterService.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> rateLimiterScript;

    public RateLimiterService(ReactiveRedisTemplate<String, String> redisTemplate, RedisScript<List> rateLimiterScript) {
        this.redisTemplate = redisTemplate;
        this.rateLimiterScript = rateLimiterScript;
    }

    public record RateLimitResult(boolean isAllowed, double remainingTokens) {}

    /**
     * Reactively checks if a request is allowed by running the Token Bucket Lua script.
     *
     * @param clientId     Unique identifier of the client (IP, API Key, etc.)
     * @param capacity     Max capacity of the bucket
     * @param refillRate   Refill rate in tokens per second
     * @return Mono containing the RateLimitResult
     */
    public Mono<RateLimitResult> isAllowed(String clientId, int capacity, double refillRate) {
        String key = "rate_limit:" + clientId;
        String nowStr = String.valueOf(System.currentTimeMillis());
        
        List<String> keys = Collections.singletonList(key);
        // Arguments: capacity, refill_rate, requested_tokens, current_time_ms
        Object[] args = new Object[]{
                String.valueOf(capacity),
                String.valueOf(refillRate),
                "1", // requested tokens
                nowStr
        };

        return redisTemplate.execute(rateLimiterScript, keys, List.of(
                String.valueOf(capacity),
                String.valueOf(refillRate),
                "1",
                nowStr
        ))
        .next() // Get the first (and only) emission of the reactive stream
        .map(resultList -> {
            if (resultList == null || resultList.size() < 2) {
                log.error("Invalid response from rate limiter Lua script for client {}: {}", clientId, resultList);
                return new RateLimitResult(true, capacity); // Fail-safe: allow request
            }
            
            // Lua numbers are returned as Long or Double depending on the Redis driver and script format
            long allowedStatus = ((Number) resultList.get(0)).longValue();
            double remaining = ((Number) resultList.get(1)).doubleValue();
            
            return new RateLimitResult(allowedStatus == 1, remaining);
        })
        .onErrorResume(ex -> {
            log.error("Error executing rate limiter Lua script for client {}. Falling back to ALLOW.", clientId, ex);
            return Mono.just(new RateLimitResult(true, capacity)); // Fail-safe
        });
    }
}
