package com.airsense.api.fusion;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class FusionProviderCache {
    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Optional<FusionProviderResponse> get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    public void put(String key, FusionProviderResponse response, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        cache.put(key, new CacheEntry(response, Instant.now().plus(ttl)));
    }

    public void clear() {
        cache.clear();
    }

    private record CacheEntry(FusionProviderResponse response, Instant expiresAt) {
    }
}
