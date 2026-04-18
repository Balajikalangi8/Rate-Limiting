package com.example.ratelimiter;

import com.example.ratelimiter.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack integration test — Spring context + embedded Tomcat on a random port.
 * The rate limiter capacity is set to 2 to exercise 429 behavior with minimal requests.
 *
 * Because all test methods share a single Spring context (performance optimization),
 * we reset the bucket state before each test so methods are independent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "rate-limiter.capacity=2",
        "rate-limiter.refill-rate=0.1",   // near-zero refill → bucket stays exhausted
        "rate-limiter.per-ip=true"
})
class TestControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void resetBuckets() {
        // Clear per-IP buckets so each test starts from a clean slate
        rateLimiterService.clearBuckets();
    }

    @Test
    void firstRequestReturns200() throws Exception {
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Request successful"));
    }

    @Test
    void requestsWithinCapacityAreAllowed() throws Exception {
        mockMvc.perform(get("/api/test")).andExpect(status().isOk());
        mockMvc.perform(get("/api/test")).andExpect(status().isOk());
    }

    @Test
    void excessRequestsReturn429() throws Exception {
        // Exhaust capacity=2
        mockMvc.perform(get("/api/test")).andExpect(status().isOk());
        mockMvc.perform(get("/api/test")).andExpect(status().isOk());

        // 3rd request must be throttled
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));
    }

    @Test
    void differentIpsBypassEachOthersBuckets() throws Exception {
        // Exhaust bucket for 10.0.0.99
        mockMvc.perform(get("/api/test").header("X-Forwarded-For", "10.0.0.99"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test").header("X-Forwarded-For", "10.0.0.99"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test").header("X-Forwarded-For", "10.0.0.99"))
                .andExpect(status().isTooManyRequests());

        // 10.0.0.100 has its own fresh bucket → allowed
        mockMvc.perform(get("/api/test").header("X-Forwarded-For", "10.0.0.100"))
                .andExpect(status().isOk());
    }

    @Test
    void responseBodyContainsTimestampOnSuccess() throws Exception {
        mockMvc.perform(get("/api/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
