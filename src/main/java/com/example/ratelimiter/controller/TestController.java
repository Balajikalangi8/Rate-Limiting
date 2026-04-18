package com.example.ratelimiter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Sample REST controller.  Rate limiting is enforced by RateLimitFilter
 * *before* this controller is ever reached — so all methods here can
 * assume they are within budget.
 */
@RestController
@RequestMapping("/api")
public class TestController {

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        return ResponseEntity.ok(Map.of(
                "status", 200,
                "message", "Request successful",
                "timestamp", Instant.now().toString()
        ));
    }

    /** Health/status endpoint excluded from rate limiting (no /api prefix). */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
