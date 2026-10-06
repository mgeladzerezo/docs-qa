package io.github.mgeladzerezo.docsqa.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A token bucket per client key: {@code permitsPerMinute} requests may be made in a burst, after
 * which the bucket refills continuously at the same rate. State is in memory, so the limit is per
 * instance; a multi-instance deployment would move the buckets to Redis or enforce it at the gateway.
 */
public class RateLimiter {

    /** Buckets above this count trigger removal of the ones that are full again, i.e. idle clients. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int permitsPerMinute, LongSupplier nanoClock) {
        this.capacity = permitsPerMinute;
        this.refillPerNano = permitsPerMinute / 60e9;
        this.nanoClock = nanoClock;
    }

    /**
     * Takes one permit for {@code key}.
     *
     * @return 0 if the request may proceed, otherwise the number of seconds until a permit is available
     */
    public long tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        long[] retryAfterSeconds = {0};
        buckets.compute(key, (_, existing) -> {
            Bucket bucket = existing == null ? new Bucket(capacity, now) : existing.refilled(now, refillPerNano, capacity);
            if (bucket.tokens >= 1) {
                return new Bucket(bucket.tokens - 1, now);
            }
            retryAfterSeconds[0] = Math.max(1, (long) Math.ceil((1 - bucket.tokens) / refillPerNano / 1e9));
            return bucket;
        });
        if (buckets.size() > SWEEP_THRESHOLD) {
            buckets.entrySet().removeIf(entry -> entry.getValue().refilled(now, refillPerNano, capacity).tokens >= capacity);
        }
        return retryAfterSeconds[0];
    }

    private record Bucket(double tokens, long updatedAt) {

        Bucket refilled(long now, double refillPerNano, int capacity) {
            return new Bucket(Math.min(capacity, tokens + (now - updatedAt) * refillPerNano), now);
        }
    }
}
