package com.airsense.api.fusion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataFusionService {
    private final List<FusionDataProvider> providers;
    private final FusionProviderCache cache;
    private final FusionCacheProperties cacheProperties;
    private final Map<String, CachedFusedSnapshot> fusedSnapshotCache = new ConcurrentHashMap<>();
    private static final long FUSED_SNAPSHOT_TTL_SECONDS = 90L;

    public CityEnvironmentalContext buildCityContext(String cityId) {
        return buildContext(FusionRequest.forCity(cityId));
    }

    public CityEnvironmentalContext buildContext(FusionRequest request) {
        FusionRequest normalizedRequest = (request != null ? request : FusionRequest.builder().build()).normalized();
        String fusedRequestKey = normalizedRequest.cacheKey();
        boolean refresh = Boolean.TRUE.equals(normalizedRequest.getParameters().get("refresh"))
                || "true".equalsIgnoreCase(String.valueOf(normalizedRequest.getParameters().getOrDefault("refresh", "")));
        if (!refresh) {
            CachedFusedSnapshot cached = fusedSnapshotCache.get(fusedRequestKey);
            if (cached != null && cached.isFresh()) {
                CityEnvironmentalContext reused = copyContext(cached.context());
                reused.getMetadata().put("snapshotReused", true);
                reused.getMetadata().put("snapshotCacheKey", cached.snapshotCacheKey());
                log.info("Fusion Snapshot Cache Hit key={} snapshotId={}", fusedRequestKey, reused.getMetadata().get("snapshotId"));
                return reused;
            }
        }
        CityEnvironmentalContext context = CityEnvironmentalContext.empty(normalizedRequest.getCityId());
        context.setTimestamp(Instant.now());
        context.getMetadata().put("fusionVersion", "phase-2-data-fusion");
        context.getMetadata().put("providerCount", providers.size());
        context.getMetadata().put("locationHash", SnapshotIdentity.locationHash(normalizedRequest.getLatitude(), normalizedRequest.getLongitude()));
        context.getMetadata().put("normalizedLatitude", SnapshotIdentity.normalizedLatitude(normalizedRequest.getLatitude()));
        context.getMetadata().put("normalizedLongitude", SnapshotIdentity.normalizedLongitude(normalizedRequest.getLongitude()));

        providers.stream()
                .sorted(Comparator.comparing(FusionDataProvider::getProviderKey))
                .forEach(provider -> {
                    FusionProviderResponse response = collect(provider, normalizedRequest);
                    attachProviderTelemetry(context, response);
                    provider.contribute(context, response);
                });

        context.getMetadata().put("generatedAt", context.getTimestamp().toString());
        attachSnapshotIdentity(context, normalizedRequest);
        context.getMetadata().put("snapshotReused", false);
        String snapshotCacheKey = snapshotCacheKey(context, normalizedRequest);
        context.getMetadata().put("snapshotCacheKey", snapshotCacheKey);
        fusedSnapshotCache.put(fusedRequestKey, new CachedFusedSnapshot(copyContext(context), Instant.now(), snapshotCacheKey));
        return context;
    }

    private FusionProviderResponse collect(FusionDataProvider provider, FusionRequest request) {
        String key = provider.getProviderKey();
        String cacheKey = key + ":" + request.cacheKey();
        if (provider.isCacheable()) {
            var cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                log.info("Using Cached {}", provider.getProviderName());
                return cached.get().toBuilder()
                        .cached(true)
                        .providerLatencyMs(0L)
                        .build();
            }
        }

        long started = System.nanoTime();
        log.info("Fetching {}", provider.getProviderName());
        try {
            FusionProviderResponse raw = provider.fetch(request);
            long latency = elapsedMillis(started);
            FusionProviderResponse response = (raw != null ? raw : FusionProviderResponse.failure(key, "Provider returned no response"))
                    .normalized(key, provider.getProviderName(), latency);

            if (response.getStatus() == FusionStatus.FAILED) {
                log.warn("{} Failed latencyMs={} errors={}", provider.getProviderName(), latency, response.getErrors());
            } else {
                log.info("{} Success status={} latencyMs={} confidence={}",
                        provider.getProviderName(), response.getStatus(), latency, response.getProviderConfidence());
                if (provider.isCacheable()) {
                    Duration ttl = provider.getCacheTtl(cacheProperties);
                    cache.put(cacheKey, response, ttl);
                }
            }
            return response;
        } catch (Exception e) {
            long latency = elapsedMillis(started);
            log.warn("{} Failed latencyMs={} reason={}", provider.getProviderName(), latency, e.getMessage());
            return FusionProviderResponse.failure(key, e.getMessage())
                    .normalized(key, provider.getProviderName(), latency);
        }
    }

    private void attachProviderTelemetry(CityEnvironmentalContext context, FusionProviderResponse response) {
        String key = response.getProviderKey();
        context.getProviderStatus().put(key, response.getProviderStatus());
        context.getProviderLatency().put(key, response.getProviderLatencyMs());
        context.getProviderConfidence().put(key, response.getProviderConfidence());
        context.getConfidenceScores().put(key, response.getProviderConfidence());
        context.getMetadata().put(key + "Cached", response.isCached());
        if (!response.getErrors().isEmpty()) {
            context.getMetadata().put(key + "Errors", response.getErrors());
        }
    }

    private void attachSnapshotIdentity(CityEnvironmentalContext context, FusionRequest request) {
        Map<String, Object> providerTimestamps = providerTimestamps(context);
        String snapshotId = SnapshotIdentity.snapshotId(request.getLatitude(), request.getLongitude(), providerTimestamps, context.getProviderStatus());
        String locationHash = SnapshotIdentity.locationHash(request.getLatitude(), request.getLongitude());
        context.getMetadata().put("snapshotId", snapshotId);
        context.getMetadata().put("locationHash", locationHash);
        context.getMetadata().put("providerTimestamps", providerTimestamps);
        context.getMetadata().put("snapshotObservedAt", firstPresent(context.getAqi(), "observedAt", "timestamp", "lastUpdated", "latestTimestamp", "fetchedAt"));
        context.getMetadata().put("snapshotGeneratedAt", context.getTimestamp().toString());
        if (context.getAqi() != null) {
            context.getAqi().put("snapshotId", snapshotId);
            context.getAqi().put("locationHash", locationHash);
        }
    }

    private String snapshotCacheKey(CityEnvironmentalContext context, FusionRequest request) {
        Map<String, Object> aqi = context.getAqi() != null ? context.getAqi() : Map.of();
        Map<String, Object> selected = aqi.get("selected") instanceof Map<?, ?> map ? (Map<String, Object>) map : aqi;
        String provider = String.valueOf(firstPresent(selected, "provider", "source"));
        String observedAt = String.valueOf(firstPresent(selected, "observedAt", "timestamp", "lastUpdated", "fetchedAt"));
        return String.join(":",
                SnapshotIdentity.locationHash(request.getLatitude(), request.getLongitude()),
                provider,
                observedAt);
    }

    private Map<String, Object> providerTimestamps(CityEnvironmentalContext context) {
        Map<String, Object> timestamps = new LinkedHashMap<>();
        putIfPresent(timestamps, "aqi", firstPresent(context.getAqi(), "timestamp", "observedAt", "lastUpdated", "latestTimestamp", "fetchedAt"));
        putIfPresent(timestamps, "weather", firstPresent(context.getWeather(), "timestamp", "lastUpdated", "responseTimestamp"));
        putIfPresent(timestamps, "satellite", firstPresent(context.getSatellite(), "timestamp", "lastUpdated"));
        putIfPresent(timestamps, "traffic", firstPresent(context.getTraffic(), "timestamp", "lastUpdated"));
        return timestamps;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) return "";
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return value;
        }
        return "";
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            map.put(key, value);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private CityEnvironmentalContext copyContext(CityEnvironmentalContext source) {
        return CityEnvironmentalContext.builder()
                .city(source.getCity())
                .cityId(source.getCityId())
                .coordinates(copyMap(source.getCoordinates()))
                .timestamp(source.getTimestamp())
                .weather(copyMap(source.getWeather()))
                .aqi(copyMap(source.getAqi()))
                .satellite(copyMap(source.getSatellite()))
                .traffic(copyMap(source.getTraffic()))
                .industries(copyList(source.getIndustries()))
                .construction(copyList(source.getConstruction()))
                .population(copyMap(source.getPopulation()))
                .greenCover(copyMap(source.getGreenCover()))
                .wind(copyMap(source.getWind()))
                .temperature(source.getTemperature())
                .humidity(source.getHumidity())
                .historicalAQI(copyList(source.getHistoricalAQI()))
                .landUse(copyMap(source.getLandUse()))
                .confidenceScores(new HashMap<>(source.getConfidenceScores() != null ? source.getConfidenceScores() : Map.of()))
                .providerStatus(new HashMap<>(source.getProviderStatus() != null ? source.getProviderStatus() : Map.of()))
                .providerLatency(new HashMap<>(source.getProviderLatency() != null ? source.getProviderLatency() : Map.of()))
                .providerConfidence(new HashMap<>(source.getProviderConfidence() != null ? source.getProviderConfidence() : Map.of()))
                .metadata(copyMap(source.getMetadata()))
                .build();
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source != null ? new HashMap<>(source) : new HashMap<>();
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> source) {
        if (source == null) return new ArrayList<>();
        return source.stream().map(this::copyMap).toList();
    }

    private record CachedFusedSnapshot(CityEnvironmentalContext context, Instant cachedAt, String snapshotCacheKey) {
        private boolean isFresh() {
            return cachedAt.plusSeconds(FUSED_SNAPSHOT_TTL_SECONDS).isAfter(Instant.now());
        }
    }
}
