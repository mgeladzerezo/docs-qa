package io.github.mgeladzerezo.docsqa.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void aBurstUpToTheLimitIsAllowedThenRefusedWithARetryDelayAndRefillsOverTime() {
        AtomicLong clock = new AtomicLong();
        RateLimiter limiter = new RateLimiter(6, clock::get);

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryAcquire("a")).isZero();
        }
        assertThat(limiter.tryAcquire("a")).isEqualTo(10);
        assertThat(limiter.tryAcquire("b")).as("another client has its own bucket").isZero();

        clock.addAndGet(10_000_000_000L);
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isPositive();
    }
}
