package com.example.ratelimiter.config;

import com.example.ratelimiter.filter.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers RateLimitFilter with an explicit order and URL pattern.
 *
 * Because RateLimitFilter is also annotated @Component, Spring Boot would
 * auto-register it with default settings.  Declaring a FilterRegistrationBean
 * lets us:
 *   1. Set a low order so rate limiting runs before any other filters.
 *   2. Scope the filter to specific URL patterns if needed.
 *   3. Disable auto-registration via setEnabled(false) on the @Component
 *      path — we control the single registration here.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitFilter filter) {

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");   // apply only to /api/** endpoints
        registration.setOrder(1);                // run first
        registration.setName("rateLimitFilter");
        return registration;
    }
}
