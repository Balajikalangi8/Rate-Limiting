package com.example.ratelimiter.service;

import com.example.ratelimiter.model.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Central rate-limiting service.
 *
 * Per-IP mode (rate-limiter.per-ip=true, the default):
 *   Each client IP address gets its own independent TokenBucket.
 *   Buckets are stored in a ConcurrentHashMap; computeIfAbsent
 *   ensures that bucket creation is both thread-safe and lock-free
 *   for the common (already-exists) path.
 *
 * Global mode (rate-limiter.per-ip=false):
 *   All clients share one bucket — useful for protecting a low-
 *   throughput backend regardless of how many callers exist.
 *
 * Memory management:
 *   A @Scheduled task evicts buckets that have been idle for longer
 *   than rate-limiter.eviction-ttl-seconds, preventing unbounded
 *   map growth from ephemeral IPs (e.g. scanners, one-time clients).
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final String GLOBAL_KEY = "__global__";

    private final RateLimitProperties props;

    /** One bucket per IP (or a single global bucket). */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterService(RateLimitProperties props) {
        this.props = props;
    }

    /**
     * Returns true if the request from the given client key is allowed.
     *
     * @param clientKey IP address string (or any per-client identifier)
     */
    public boolean isAllowed(String clientKey) {
        String key = props.isPerIp() ? clientKey : GLOBAL_KEY;
        TokenBucket bucket = buckets.computeIfAbsent(key, k ->
                new TokenBucket(props.getCapacity(), props.getRefillRate()));
        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.warn("Rate limit exceeded for client={} bucket_tokens={}",
                    key, String.format("%.2f", bucket.getAvailableTokens()));
        }
        return allowed;
    }

    /**
     * Evicts TokenBucket entries that have been idle beyond the configured TTL.
     * Runs every 60 seconds; initial delay avoids startup noise.
     */
    @Scheduled(fixedDelayString = "60000", initialDelayString = "60000")
    public void evictStaleBuckets() {
        long cutoffMs = System.currentTimeMillis()
                - props.getEvictionTtlSeconds() * 1_000L;

        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().getLastAccessMs() < cutoffMs);
        int removed = before - buckets.size();

        if (removed > 0) {
            log.info("Evicted {} stale rate-limit bucket(s); active buckets={}",
                    removed, buckets.size());
        }
    }

    /** Exposed for testing and monitoring. */
    public int getActiveBucketCount() {
        return buckets.size();
    }

    /** Clears all bucket state. For use in tests only. */
    public void clearBuckets() {
        buckets.clear();
    }
}
