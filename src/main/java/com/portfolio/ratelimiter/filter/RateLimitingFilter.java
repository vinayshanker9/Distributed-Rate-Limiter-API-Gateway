package com.portfolio.ratelimiter.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ratelimiter.service.RateLimiterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitingFilter implements WebFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RateLimitingFilter.class);

    private final RateLimiterService rateLimiterService;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(RateLimiterService rateLimiterService, ObjectMapper objectMapper) {
        this.rateLimiterService = rateLimiterService;
        this.objectMapper = objectMapper;
    }

    @Value("${app.rate-limiter.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limiter.default-capacity:10}")
    private int defaultCapacity;

    @Value("${app.rate-limiter.default-refill-rate:2.0}")
    private double defaultRefillRate;

    @Value("${app.rate-limiter.header-api-key:X-API-Key}")
    private String apiKeyHeader;

    @Value("${app.rate-limiter.exclude-paths:}")
    private List<String> excludePaths;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 1. Check for bypass / exclusion paths
        if (excludePaths != null && excludePaths.stream().anyMatch(path::startsWith)) {
            log.debug("Path {} is excluded from rate limiting", path);
            return chain.filter(exchange);
        }

        // 2. Identify the client (API Key takes precedence over IP Address)
        String clientId = resolveClientId(request);

        // 3. Check for dynamic overrides in request headers (Premium Demo Feature!)
        int capacity = resolveCapacity(request);
        double refillRate = resolveRefillRate(request);

        // 4. Evaluate Rate Limiting
        return rateLimiterService.isAllowed(clientId, capacity, refillRate)
                .flatMap(result -> {
                    ServerHttpResponse response = exchange.getResponse();
                    
                    // Set rate limit metadata in standard response headers
                    response.getHeaders().add("X-RateLimit-Limit", String.valueOf(capacity));
                    response.getHeaders().add("X-RateLimit-Remaining", String.valueOf((int) Math.floor(result.remainingTokens())));
                    
                    if (result.isAllowed()) {
                        log.debug("Request ALLOWED for client {}. Remaining tokens: {}", clientId, result.remainingTokens());
                        return chain.filter(exchange);
                    } else {
                        log.warn("Request DENIED for client {}. Rate limit exceeded.", clientId);
                        return handleRateLimitExceeded(exchange, capacity, refillRate);
                    }
                });
    }

    private String resolveClientId(ServerHttpRequest request) {
        // Look up X-API-Key
        String apiKey = request.getHeaders().getFirst(apiKeyHeader);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return "apikey:" + apiKey.trim();
        }

        // Fallback to Remote IP Address
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return "ip:" + remoteAddress.getAddress().getHostAddress();
        }

        return "anonymous";
    }

    private int resolveCapacity(ServerHttpRequest request) {
        String override = request.getHeaders().getFirst("X-RateLimit-Config-Capacity");
        if (override != null) {
            try {
                return Integer.parseInt(override);
            } catch (NumberFormatException e) {
                log.warn("Invalid capacity override header: {}", override);
            }
        }
        return defaultCapacity;
    }

    private double resolveRefillRate(ServerHttpRequest request) {
        String override = request.getHeaders().getFirst("X-RateLimit-Config-RefillRate");
        if (override != null) {
            try {
                return Double.parseDouble(override);
            } catch (NumberFormatException e) {
                log.warn("Invalid refill rate override header: {}", override);
            }
        }
        return defaultRefillRate;
    }

    private Mono<Void> handleRateLimitExceeded(ServerWebExchange exchange, int capacity, double refillRate) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        // Retry-After header represents standard recommendation to wait (in seconds)
        // Here we suggest waiting at least 1 second
        response.getHeaders().add("Retry-After", "1");

        Map<String, Object> errorBody = Map.of(
                "error", "Too Many Requests",
                "message", "Rate limit exceeded. Please try again later.",
                "limit", capacity,
                "refill_rate_seconds", refillRate
        );

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorBody);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize error body", e);
            bytes = "{\"error\":\"Too Many Requests\"}".getBytes();
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
