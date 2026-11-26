package com.menumaestro.backend.security;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import com.menumaestro.backend.exception.ApiException;

/** Fixed-window in-memory rate limiter shared by all routes of this single API instance. */
@Component
public class RateLimiter {
  private record Bucket(int[] count, long until) {}

  private final Map<String, Bucket> buckets = new HashMap<>();
  private final LongSupplier now;

  public RateLimiter() { this(System::currentTimeMillis); }

  public RateLimiter(LongSupplier now) { this.now = now; }

  public void limit(String key, int limit) { limit(key, limit, 60000); }

  public synchronized void limit(String key, int limit, long windowMs) {
    long time = now.getAsLong();
    if (buckets.size() > 10000) buckets.values().removeIf(bucket -> bucket.until() < time);
    Bucket bucket = buckets.get(key);
    if (bucket == null || bucket.until() < time) {
      bucket = new Bucket(new int[] {0}, time + windowMs);
      buckets.put(key, bucket);
    }
    if (++bucket.count()[0] > limit) throw new ApiException(429, "Too many requests. Please try again shortly.");
  }
}
