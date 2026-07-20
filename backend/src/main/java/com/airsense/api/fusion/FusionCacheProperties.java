package com.airsense.api.fusion;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "fusion.cache")
public class FusionCacheProperties {
    private long defaultTtlSeconds = 600;
    private Map<String, Long> providerTtlSeconds = new HashMap<>();

    public Duration ttlFor(String providerKey) {
        long seconds = providerTtlSeconds.getOrDefault(providerKey, defaultTtlSeconds);
        return Duration.ofSeconds(Math.max(0, seconds));
    }
}
