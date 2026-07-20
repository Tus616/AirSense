package com.airsense.api.services;

import com.airsense.api.fusion.SnapshotIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class IqAirService {
    private final RestTemplate restTemplate;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Value("${iqair.api.key:${IQAIR_API_KEY:}}")
    private String apiKey;

    @Value("${iqair.base-url:https://api.airvisual.com}")
    private String baseUrl;

    @Value("${iqair.cache-ttl-seconds:300}")
    private long cacheTtlSeconds;

    public IqAirService(RestTemplateBuilder builder,
                        @Value("${iqair.request-timeout-seconds:${IQAIR_REQUEST_TIMEOUT_SECONDS:10}}") long requestTimeoutSeconds) {
        long timeout = Math.max(1, requestTimeoutSeconds);
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(timeout))
                .setReadTimeout(Duration.ofSeconds(timeout))
                .build();
    }

    public Map<String, Object> getNearestCityAqi(double latitude, double longitude) {
        if (!validCoordinates(latitude, longitude)) {
            return unavailable(latitude, longitude, "Invalid coordinates", "MISS", null);
        }
        String cacheKey = SnapshotIdentity.normalizedLatitude(latitude) + ":" + SnapshotIdentity.normalizedLongitude(longitude);
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
            Map<String, Object> data = new LinkedHashMap<>(cached.data());
            data.put("cacheStatus", "HIT");
            return data;
        }
        if (apiKey == null || apiKey.isBlank()) {
            Map<String, Object> data = unavailable(latitude, longitude, "IQAIR_API_KEY is not configured", "MISS", null);
            cache.put(cacheKey, new CacheEntry(data, Instant.now()));
            return data;
        }

        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .pathSegment("v2", "nearest_city")
                .queryParam("lat", latitude)
                .queryParam("lon", longitude)
                .queryParam("key", apiKey)
                .build()
                .toUriString();
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            Map<String, Object> body = response.getBody() != null ? response.getBody() : Map.of();
            Map<String, Object> result = parse(latitude, longitude, body, response.getStatusCode().value());
            result.put("cacheStatus", "MISS");
            cache.put(cacheKey, new CacheEntry(new LinkedHashMap<>(result), Instant.now()));
            return result;
        } catch (RestClientException e) {
            log.warn("IQAir AirVisual current AQI unavailable lat={} lon={} reason={}", latitude, longitude, e.getMessage());
            Map<String, Object> data = unavailable(latitude, longitude, e.getMessage(), "MISS", null);
            cache.put(cacheKey, new CacheEntry(new LinkedHashMap<>(data), Instant.now()));
            return data;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(double latitude, double longitude, Map<String, Object> body, Integer httpStatus) {
        if (!"success".equalsIgnoreCase(String.valueOf(body.get("status")))) {
            return unavailable(latitude, longitude, "IQAir status=" + body.getOrDefault("status", "unknown"), "MISS", httpStatus);
        }
        Map<String, Object> data = body.get("data") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Object> current = data.get("current") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Map<String, Object> pollution = current.get("pollution") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        Object rawAqi = pollution.get("aqius");
        if (!(rawAqi instanceof Number number)) {
            return unavailable(latitude, longitude, "IQAir response missing current.pollution.aqius", "MISS", httpStatus);
        }
        int aqi = number.intValue();
        String observedAt = text(pollution.get("ts"));
        if (observedAt.isBlank()) {
            observedAt = Instant.now().toString();
        }
        Map<String, Object> providerCoordinates = coordinates(data.get("location"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("aqi", aqi);
        result.put("currentAqi", aqi);
        result.put("canonicalAqi", aqi);
        result.put("aqiStandard", "US AQI");
        result.put("aqiCategory", usAqiCategory(aqi));
        result.put("sourceType", "IQAIR_AIRVISUAL_OBSERVED");
        result.put("sourceLabel", "IQAir AirVisual US AQI");
        result.put("provider", "IQAir AirVisual");
        result.put("providerStatus", "SUCCESS");
        result.put("city", text(data.get("city")));
        result.put("state", text(data.get("state")));
        result.put("country", text(data.get("country")));
        result.put("stationName", stationName(data));
        result.put("coordinates", Map.of("latitude", latitude, "longitude", longitude));
        result.put("providerCoordinates", providerCoordinates);
        result.put("observedAt", observedAt);
        result.put("timestamp", observedAt);
        result.put("latestTimestamp", observedAt);
        result.put("lastUpdated", observedAt);
        result.put("fetchedAt", Instant.now().toString());
        result.put("freshnessStatus", "LIVE");
        result.put("httpStatus", httpStatus);
        result.put("rawIqAir", body);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coordinates(Object location) {
        if (location instanceof Map<?, ?> map) {
            Object raw = ((Map<String, Object>) map).get("coordinates");
            if (raw instanceof List<?> list && list.size() >= 2
                    && list.get(0) instanceof Number lon && list.get(1) instanceof Number lat) {
                return Map.of("latitude", lat.doubleValue(), "longitude", lon.doubleValue());
            }
        }
        return Map.of();
    }

    private Map<String, Object> unavailable(double latitude, double longitude, String reason, String cacheStatus, Integer httpStatus) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("aqi", null);
        result.put("currentAqi", null);
        result.put("canonicalAqi", null);
        result.put("aqiStandard", "US AQI");
        result.put("aqiCategory", "UNAVAILABLE");
        result.put("sourceType", "UNAVAILABLE");
        result.put("sourceLabel", "IQAir AirVisual US AQI unavailable");
        result.put("provider", "IQAir AirVisual");
        result.put("providerStatus", "UNAVAILABLE");
        result.put("coordinates", Map.of("latitude", latitude, "longitude", longitude));
        result.put("observedAt", "");
        result.put("timestamp", "");
        result.put("fetchedAt", Instant.now().toString());
        result.put("freshnessStatus", "UNAVAILABLE");
        result.put("reason", reason);
        result.put("cacheStatus", cacheStatus);
        if (httpStatus != null) {
            result.put("httpStatus", httpStatus);
        }
        return result;
    }

    private String stationName(Map<String, Object> data) {
        String city = text(data.get("city"));
        String state = text(data.get("state"));
        String label = List.of(city, state).stream().filter(value -> !value.isBlank()).reduce((a, b) -> a + ", " + b).orElse("Nearest city");
        return label + " - IQAir AirVisual";
    }

    private String usAqiCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for Sensitive Groups";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very Unhealthy";
        return "Hazardous";
    }

    private boolean validCoordinates(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private String text(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    private record CacheEntry(Map<String, Object> data, Instant cachedAt) {
        boolean isExpired(long ttlSeconds) {
            return cachedAt.plusSeconds(Math.min(300, Math.max(0, ttlSeconds))).isBefore(Instant.now());
        }
    }
}
