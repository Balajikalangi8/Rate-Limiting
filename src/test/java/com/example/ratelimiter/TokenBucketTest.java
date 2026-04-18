package com.example.ratelimiter;

import com.example.ratelimiter.service.TokenBucket;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketTest {

    // ── Basic correctness ────────────────────────────────────────────────────

    @Test
    void newBucketStartsFull() {
        TokenBucket bucket = new TokenBucket(5, 1.0);
        assertThat(bucket.getAvailableTokens()).isEqualTo(5.0);
    }

    @Test
    void allowsUpToCapacityRequests() {
        TokenBucket bucket = new TokenBucket(3, 1.0);
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();  // bucket empty
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        // capacity=2, refill=10/sec → full refill in ~200 ms
        TokenBucket bucket = new TokenBucket(2, 10.0);
        bucket.tryConsume();
        bucket.tryConsume();
        assertThat(bucket.tryConsume()).isFalse();

        Thread.sleep(250);  // wait for at least 2 tokens to refill

        assertThat(bucket.tryConsume()).isTrue();
    }

    @Test
    void tokenCountNeverExceedsCapacity() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5, 100.0);
        Thread.sleep(200);  // generous wait — would overfill without cap
        assertThat(bucket.getAvailableTokens()).isLessThanOrEqualTo(5.0);
    }

    @Test
    void constructorRejectsInvalidCapacity() {
        assertThatThrownBy(() -> new TokenBucket(0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsInvalidRefillRate() {
        assertThatThrownBy(() -> new TokenBucket(10, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    /**
     * 20 threads each fire 10 requests simultaneously against a bucket
     * with capacity=10 and a slow refill rate (0.01 tokens/sec — negligible).
     * Exactly 10 of the 200 total requests should succeed.
     */
    @Test
    void concurrentRequestsRespectCapacity() throws InterruptedException {
        int capacity = 10;
        int threads = 20;
        int requestsPerThread = 10;
        TokenBucket bucket = new TokenBucket(capacity, 0.01);  // near-zero refill

        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger denied = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try { barrier.await(); } catch (Exception ignored) {}
                for (int j = 0; j < requestsPerThread; j++) {
                    if (bucket.tryConsume()) allowed.incrementAndGet();
                    else denied.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(5, TimeUnit.SECONDS); } catch (Exception e) { /* propagate below */ }
        }
        pool.shutdown();

        assertThat(allowed.get()).isEqualTo(capacity);
        assertThat(denied.get()).isEqualTo(threads * requestsPerThread - capacity);
    }

    /**
     * Verifies that concurrent refill + consume does not corrupt state
     * by checking tokens never go negative.
     */
    @Test
    void tokensNeverGoNegativeUnderConcurrency() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(5, 50.0);  // fast refill
        AtomicInteger violations = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);

        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                for (int j = 0; j < 500; j++) {
                    bucket.tryConsume();
                    if (bucket.getAvailableTokens() < 0) violations.incrementAndGet();
                }
            });
        }

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(violations.get()).isZero();
    }

    // ── Refill timing accuracy ────────────────────────────────────────────────

    @Test
    void refillRateMatchesConfiguration() {
        // 10 tokens/sec → after 1 second a drained bucket should be ~full
        TokenBucket bucket = new TokenBucket(10, 10.0);
        // drain it
        for (int i = 0; i < 10; i++) bucket.tryConsume();
        assertThat(bucket.getAvailableTokens()).isLessThan(1.0);

        Awaitility.await()
                .atMost(1500, TimeUnit.MILLISECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(bucket.getAvailableTokens()).isGreaterThanOrEqualTo(9.0));
    }
}
