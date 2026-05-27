package com.portfolio.ratelimiter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class GatewayController {

    /**
     * A rate-limited resource representing high-value API endpoints.
     */
    @GetMapping("/resource")
    public Mono<ResponseEntity<Map<String, Object>>> getRateLimitedResource() {
        return Mono.just(
                ResponseEntity.ok(
                        Map.of(
                                "status", "success",
                                "message", "Welcome to the Secure Backend API!",
                                "requestId", UUID.randomUUID().toString(),
                                "payload", Map.of(
                                        "data", "High-value business intelligence dataset",
                                        "serverNode", "node-reactive-01"
                                )
                        )
                )
        );
    }

    /**
     * An unlimited resource representing static assets or health indicators.
     */
    @GetMapping("/unlimited")
    public Mono<ResponseEntity<Map<String, Object>>> getUnlimitedResource() {
        return Mono.just(
                ResponseEntity.ok(
                        Map.of(
                                "status", "success",
                                "message", "This is an UNLIMITED public endpoint. Bypasses rate limiter check.",
                                "requestId", UUID.randomUUID().toString()
                        )
                )
        );
    }
}
