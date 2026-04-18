package com.example.ratelimiter.service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * ════════════════════════════════════════════════════════════════
 *  TOKEN BUCKET ALGORITHM
 * ════════════════════════════════════════════════════════════════
 *
 *  Concept
 *  ───────
 *  Imagine a physical bucket with a fixed capacity (e.g. 10 tokens).
 *  • Tokens drip into the bucket at a constant rate (e.g. 5/sec).
 *  • Each incoming request tries to remove ONE token.
 *    – If a token is available → request is allowed, token is consumed.
 *    – If the bucket is empty  → request is rejected (429).
 *  • Excess tokens that overflow the bucket are discarded, so the
 *    bucket never holds more than its capacity (burst limit).
 *
 *  Properties
 *  ──────────
 *  • Burst tolerance: up to `capacity` requests can pass instantly
 *    before throttling kicks in.
 *  • Sustained throughput: at most `refillRate` requests/second
 *    over a long window.
 *  • Memory-efficient: a single bucket needs only ~3 longs.
 *
 *  Thread Safety
 *  ─────────────
 *  A ReentrantLock guards the mutable state (token count +
 *  lastRefillTimestamp) so that the "check-then-act" sequence
 *  is atomic.  We deliberately avoid AtomicLong here because
 *  the refill calculation reads *and* writes two fields that must
 *  be consistent with each other — a single compare-and-swap
 *  cannot protect both.
 */
public class TokenBucket {

    private final long capacity;

    /**
     * Refill rate expressed in tokens-per-nanosecond for precision.
     * Stored as double so fractional rates (e.g. 0.5 tokens/sec) work.
     */
    private final double refillRatePerNano;

    /** Current token count; may be fractional between refills. */
    private double tokens;

    /** Nanosecond timestamp of the last refill computation. */
    private long lastRefillNanos;

    /** Tracks the last access time (wall-clock ms) for eviction decisions. */
    private volatile long lastAccessMs;

    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param capacity        maximum tokens the bucket can hold
     * @param refillRatePerSec tokens added per second
     */
    public TokenBucket(long capacity, double refillRatePerSec) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        if (refillRatePerSec <= 0) throw new IllegalArgumentException("refillRate must be > 0");

        this.capacity = capacity;
        this.refillRatePerNano = refillRatePerSec / 1_000_000_000.0;
        this.tokens = capacity;          // start full so burst is immediately available
        this.lastRefillNanos = System.nanoTime();
        this.lastAccessMs = System.currentTimeMillis();
    }

    /**
     * Attempts to consume one token.
     *
     * @return {@code true} if the token was consumed (request allowed),
     *         {@code false} if the bucket was empty (request throttled)
     */
    public boolean tryConsume() {
        lock.lock();
        try {
            refill();
            lastAccessMs = System.currentTimeMillis();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds tokens proportional to the time elapsed since the last refill.
     * Called inside the lock to keep the two-field update atomic.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) return;            // clock jitter guard

        double tokensToAdd = elapsedNanos * refillRatePerNano;
        tokens = Math.min(capacity, tokens + tokensToAdd);
        lastRefillNanos = now;
    }

    /** Returns the last wall-clock access time in milliseconds. */
    public long getLastAccessMs() {
        return lastAccessMs;
    }

    /** Snapshot of current available tokens (for monitoring / tests). */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return tokens;
        } finally {
            lock.unlock();
        }
    }
}
