package com.example.ratelimiter.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for the rate limiter, bound from application.yml
 * under the "rate-limiter" prefix.
 */
@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimitProperties {

    /** Maximum tokens the bucket can hold (burst capacity). */
    private long capacity = 10;

    /** Tokens added per second (steady-state throughput). */
    private double refillRate = 5;

    /** When true, each client IP gets its own independent bucket. */
    private boolean perIp = true;

    /** Seconds of inactivity after which a per-IP bucket is evicted. */
    private long evictionTtlSeconds = 300;

    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }

    public double getRefillRate() { return refillRate; }
    public void setRefillRate(double refillRate) { this.refillRate = refillRate; }

    public boolean isPerIp() { return perIp; }
    public void setPerIp(boolean perIp) { this.perIp = perIp; }

    public long getEvictionTtlSeconds() { return evictionTtlSeconds; }
    public void setEvictionTtlSeconds(long evictionTtlSeconds) {
        this.evictionTtlSeconds = evictionTtlSeconds;
    }
}
