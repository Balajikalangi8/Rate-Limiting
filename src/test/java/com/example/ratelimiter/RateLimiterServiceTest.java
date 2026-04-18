package com.example.ratelimiter;

import com.example.ratelimiter.model.RateLimitProperties;
import com.example.ratelimiter.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    private RateLimiterService service;
    private RateLimitProperties props;

    @BeforeEach
    void setup() {
        props = new RateLimitProperties();
        props.setCapacity(3);
        props.setRefillRate(1.0);
        props.setPerIp(true);
        props.setEvictionTtlSeconds(60);
        service = new RateLimiterService(props);
    }

    @Test
    void allowsRequestsUpToCapacityPerIp() {
        assertThat(service.isAllowed("10.0.0.1")).isTrue();
        assertThat(service.isAllowed("10.0.0.1")).isTrue();
        assertThat(service.isAllowed("10.0.0.1")).isTrue();
        assertThat(service.isAllowed("10.0.0.1")).isFalse();  // 4th exceeds capacity=3
    }

    @Test
    void differentIpsHaveIndependentBuckets() {
        // Exhaust IP A
        for (int i = 0; i < 3; i++) service.isAllowed("10.0.0.1");
        assertThat(service.isAllowed("10.0.0.1")).isFalse();

        // IP B is completely unaffected
        assertThat(service.isAllowed("10.0.0.2")).isTrue();
    }

    @Test
    void globalModeSharesOneBucket() {
        props.setPerIp(false);
        // Exhaust global bucket
        for (int i = 0; i < 3; i++) service.isAllowed("10.0.0.1");

        // Different IP, same global bucket → should be rejected
        assertThat(service.isAllowed("10.0.0.2")).isFalse();
    }

    @Test
    void bucketCountGrowsWithUniqueIps() {
        service.isAllowed("1.1.1.1");
        service.isAllowed("2.2.2.2");
        service.isAllowed("3.3.3.3");
        assertThat(service.getActiveBucketCount()).isEqualTo(3);
    }

    @Test
    void sameIpDoesNotCreateDuplicateBuckets() {
        for (int i = 0; i < 10; i++) service.isAllowed("10.0.0.1");
        assertThat(service.getActiveBucketCount()).isEqualTo(1);
    }
}
